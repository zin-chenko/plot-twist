#!/usr/bin/env python3
"""Local PostgreSQL E2E: user ownership, keyset feed, concurrent votes and comments."""
import concurrent.futures
import json
import os
import re
import secrets
import struct
import zlib
import time
import uuid
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, urlopen
from auth_support import BASE, user_grant, machine_token
MAIL = os.getenv('MAILPIT_URL', 'http://127.0.0.1:' + os.getenv('MAILPIT_PORT', '8025'))


def request(method, path, body=None, token=None, headers=None, expected=200):
    headers = dict(headers or {})
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if isinstance(body, dict):
        body = json.dumps(body).encode()
        headers['Content-Type'] = 'application/json'
    req = Request(BASE + path, data=body, headers=headers, method=method)
    for attempt in range(6):
        try:
            response = urlopen(req, timeout=30)
        except HTTPError as error:
            response = error
        if response.status != 429 or not path.startswith('/auth/v1/') or attempt == 5:
            break
        # Setup accounts share the gateway IP with the other local integration checks.
        delay = min(30, max(1, int(response.headers.get('Retry-After', '12'))))
        response.close()
        time.sleep(delay)
    with response:
        assert response.status == expected, f'{method} {path}: expected {expected}, got {response.status}'
        data = response.read()
        if data and 'json' in response.headers.get('Content-Type', ''):
            data = json.loads(data)
        return data, response.headers


def mail_code(address, subject):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        with urlopen(MAIL + '/api/v1/messages', timeout=5) as response:
            messages = json.load(response).get('messages', [])
        for message in messages:
            if subject not in message.get('Subject', ''):
                continue
            if not any(person.get('Address') == address for person in message.get('To', [])):
                continue
            with urlopen(MAIL + '/api/v1/message/' + message['ID'], timeout=5) as response:
                text = json.load(response).get('Text', '')
            match = re.search(r'(?m)^([A-Za-z0-9_-]{43})\r?$', text)
            if match:
                return match.group(1)
        time.sleep(1)
    raise AssertionError('Expected verification/reset email was not delivered')

def png():
    def chunk(kind, data):
        return struct.pack('!I', len(data)) + kind + data + struct.pack('!I', zlib.crc32(kind + data) & 0xffffffff)
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!IIBBBBB', 2, 2, 8, 2, 0, 0, 0))
            + chunk(b'tEXt', b'Comment\x00PRIVATE_METADATA_MARKER')
            + chunk(b'IDAT', zlib.compress(b'\x00\xff\x00\x00\x00\xff\x00' * 2)) + chunk(b'IEND', b''))


def account():
    address = 'e2e-' + uuid.uuid4().hex + '@example.test'
    password = 'Test-' + secrets.token_urlsafe(24)
    request('POST', '/auth/v1/registrations', {'email': address, 'password': password}, expected=202)
    code = mail_code(address, 'Verify')
    request('POST', '/auth/v1/email-verifications', {'token': code}, expected=204)
    request('POST', '/auth/v1/email-verifications', {'token': code}, expected=400)
    tokens, _ = user_grant('openid api.read api.write', address, password)
    return address, password, tokens['access_token']


def check():
    email,password,author=account()
    _,_,reader=account()
    path='/api/v1/stories'
    created=[]
    try:
        request('GET',path,expected=401)
        request('GET',path,token=machine_token(),expected=403)
        read_only=user_grant('openid api.read',email,password)[0]['access_token']
        request('POST',path,{'title':'No write scope','body':'Text'},read_only,expected=403)
        for bad in [{'title':' ','body':'Text'},{'title':'x'*161,'body':'Text'},
                    {'title':'Valid','body':'x'*20001},{'title':'Valid','body':'bad\u0000text'},
                    {'title':'Valid','body':'Text','authorId':str(uuid.uuid4())}]:
            request('POST',path,bad,author,expected=400)
        for i in range(3):
            story,h=request('POST',path,{'title':f'История {i}','body':'Текст истории. <script>alert(1)</script>'},author,expected=201)
            created.append(story['id'])
            assert h['ETag']=='"0"' and h['Cache-Control']=='no-store'
            assert 'email' not in story and 'birthDate' not in story and story['authorName']=='Читатель'
        sid=created[0]
        route=path+'/'+sid
        story,h=request('GET',route,token=reader)
        request('PUT',route,{'title':'Stolen','body':'Text'},reader,{'If-Match':h['ETag']},expected=403)
        request('DELETE',route,token=reader,headers={'If-Match':h['ETag']},expected=403)
        request('PUT',route,{'title':'Valid','body':'Text'},author,expected=428)
        # Competing author writes must allow only one writer for the same version.
        def edit(i):
            try:
                request('PUT',route,{'title':f'Edited {i}','body':'Edited body'},author,{'If-Match':'"0"'})
                return 200
            except AssertionError as error:
                assert 'got 412' in str(error), str(error)
                return 412
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            assert sorted(pool.map(edit,range(2)))==[200,412]
        request('DELETE',route,token=author,headers={'If-Match':'"0"'},expected=412)
        private_profile={'displayName':'Автор истории','givenName':'PRIVATE_NAME','familyName':None,'bio':'PRIVATE_BIO','birthDate':'1990-01-02'}
        request('PUT','/api/v1/profile',private_profile,author,{'If-None-Match':'*'},expected=201)
        named,_=request('GET',route,token=reader)
        assert named['authorName']=='Автор истории' and 'PRIVATE_' not in json.dumps(named)
        request('GET',route+'/author/photo',token=reader,expected=404)
        request('GET',route+'/author/photo',expected=401)
        request('GET',route+'/author/photo',token=machine_token(),expected=403)
        _,ph=request('GET','/api/v1/profile',token=author)
        uploaded,_=request('PUT','/api/v1/profile/photo',png(),author,{'If-Match':ph['ETag'],'Content-Type':'application/octet-stream'})
        named,_=request('GET',route,token=reader)
        assert named['authorPhotoSha256']==uploaded['photo']['sha256']
        avatar,ah=request('GET',route+'/author/photo',token=reader)
        assert avatar.startswith(b'\xff\xd8') and ah['Content-Type']=='image/jpeg' and ah['Cache-Control']=='no-store'
        assert b'PRIVATE_METADATA_MARKER' not in avatar
        # A single account can switch/remove its vote, never accumulate duplicates.
        for value in [1,1,-1,-1,0,0]:
            story,_=request('PUT',route+'/reaction',{'value':value},reader)
            assert (story['likes'],story['dislikes'],story['myReaction'])==(int(value==1),int(value==-1),value)
        for bad in [{'value':2},{'value':None},{'value':1.5},{'value':'1'},{'value':True},{'value':1,'accountId':str(uuid.uuid4())}]:
            request('PUT',route+'/reaction',bad,reader,expected=400)
        def vote(i): return request('PUT',route+'/reaction',{'value':1 if i%2 else -1},reader)[0]
        with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool: list(pool.map(vote,range(12)))
        story,_=request('GET',route,token=reader)
        assert story['likes']+story['dislikes']==1 and story['myReaction'] in [-1,1]
        # A different account has its own independent vote.
        story,_=request('PUT',route+'/reaction',{'value':1},author)
        assert story['likes']+story['dislikes']==2
        request('PUT',route+'/reaction',{'value':1},reader)
        second=path+'/'+created[1]
        request('PUT',second+'/reaction',{'value':1},reader)
        liked,_=request('GET',path+'?liked=true&limit=1',token=reader)
        assert len(liked['items'])==1 and liked['items'][0]['id']==created[1] and liked['nextCursor']
        tail,_=request('GET',path+'?liked=true&limit=1&cursor='+quote(liked['nextCursor']),token=reader)
        assert [s['id'] for s in tail['items']]==[sid]
        request('PUT',second+'/reaction',{'value':0},reader)
        assert [s['id'] for s in request('GET',path+'?liked=true',token=reader)[0]['items']]==[sid]
        request('PUT',route+'/reaction',{'value':-1},reader)
        assert not request('GET',path+'?liked=true',token=reader)[0]['items']
        assert [s['id'] for s in request('GET',path+'?liked=true',token=author)[0]['items']]==[sid]
        comments=[]
        for i in range(3):
            comment,_=request('POST',route+'/comments',{'body':f'Комментарий {i}'},reader,expected=201)
            comments.append(comment['id'])
        comment_path=route+'/comments/'+comments[0]
        current,ch=request('GET',comment_path,token=author)
        assert current['version']==0 and ch['ETag']=='"0"'
        request('PUT',comment_path,{'body':'Stolen'},author,{'If-Match':ch['ETag']},expected=403)
        request('PUT',comment_path,{'body':'New'},reader,expected=428)
        request('PUT',comment_path,{'body':' '},reader,{'If-Match':ch['ETag']},expected=400)
        request('PUT',comment_path,{'body':'New','authorId':str(uuid.uuid4())},reader,{'If-Match':ch['ETag']},expected=400)
        request('PUT',comment_path,{'body':'New'},read_only,{'If-Match':ch['ETag']},expected=403)
        request('PUT',path+'/'+created[1]+'/comments/'+comments[0],{'body':'Wrong story'},reader,{'If-Match':ch['ETag']},expected=404)
        def edit_comment(i):
            try:
                request('PUT',comment_path,{'body':f'Edited comment {i}'},reader,{'If-Match':ch['ETag']})
                return 200
            except AssertionError as error:
                assert 'got 412' in str(error), str(error)
                return 412
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            assert sorted(pool.map(edit_comment,range(2)))==[200,412]
        updated,uh=request('GET',comment_path,token=author)
        assert updated['version']==1 and uh['ETag']=='"1"' and updated['body'].startswith('Edited comment')
        assert request('GET',route,token=author)[0]['commentCount']==3
        request('POST',route+'/comments',{'body':' '},reader,expected=400)
        request('DELETE',route+'/comments/'+comments[0],token=author,expected=403)
        page,_=request('GET',route+'/comments?limit=2',token=author)
        assert len(page['items'])==2 and page['nextCursor']
        rest,_=request('GET',route+'/comments?limit=2&cursor='+quote(page['nextCursor']),token=author)
        assert len(rest['items'])==1 and not rest['nextCursor']
        assert {c['id'] for c in page['items']+rest['items']}==set(comments)
        request('DELETE',route+'/comments/'+comments[0],token=reader,expected=204)
        story,_=request('GET',route,token=author)
        assert story['commentCount']==2
        # Cursor pagination remains stable when new posts arrive and the anchor is deleted.
        page,_=request('GET',path+'?mine=true&limit=2',token=author)
        assert len(page['items'])==2 and page['nextCursor']
        new,_=request('POST',path,{'title':'New arrival','body':'After page one'},author,expected=201)
        created.append(new['id'])
        anchor=page['items'][-1]
        request('DELETE',path+'/'+anchor['id'],token=author,headers={'If-Match':'"'+str(anchor['version'])+'"'},expected=204)
        rest,_=request('GET',path+'?mine=true&limit=2&cursor='+quote(page['nextCursor']),token=author)
        assert len(rest['items'])==1 and rest['items'][0]['id'] not in [s['id'] for s in page['items']]
        request('GET',path+'?limit=21',token=reader,expected=400)
        request('GET',path+'?cursor=garbage',token=reader,expected=400)
        import base64
        extreme=base64.urlsafe_b64encode(('+1000000000-12-31T23:59:59.999999999Z|'+str(uuid.uuid4())).encode()).decode().rstrip('=')
        request('GET',path+'?cursor='+extreme,token=reader,expected=400)
        assert not request('GET',path+'?mine=true',token=reader)[0]['items']
        _,ph=request('GET','/api/v1/profile',token=author)
        request('DELETE','/api/v1/profile/photo',token=author,headers={'If-Match':ph['ETag']})
        assert request('GET',route,token=reader)[0]['authorPhotoSha256'] is None
        request('GET',route+'/author/photo',token=reader,expected=404)
        # Deleting a story removes its discussion/reactions as well.
        current,h=request('GET',route,token=author)
        request('DELETE',route,token=author,headers={'If-Match':h['ETag']},expected=204)
        request('GET',route,token=reader,expected=404)
        request('GET',route+'/comments',token=reader,expected=404)
        request('PUT',route+'/reaction',{'value':1},reader,expected=404)
        request('POST',route+'/comments',{'body':'Gone'},reader,expected=404)
        request('DELETE',route+'/comments/'+comments[-1],token=reader,expected=404)
        print('PASS: real registration/login; private keyset feed; author-only edits/deletes; lost-update prevention; concurrent unique votes; comment edits/CAS; personal liked feed; authenticated author photos; cascade deletion')
    finally:
        for sid in created:
            try:
                story,h=request('GET',path+'/'+sid,token=author)
                request('DELETE',path+'/'+sid,token=author,headers={'If-Match':h['ETag']},expected=204)
            except AssertionError: pass
        try:
            _,h=request('GET','/api/v1/profile',token=author)
            request('DELETE','/api/v1/profile',token=author,headers={'If-Match':h['ETag']},expected=204)
        except AssertionError: pass

if __name__=='__main__': check()
