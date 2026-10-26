package io.plottwist.domain;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProfileService {
    private static final String SELECT = """
        SELECT p.*, m.sha256, m.size_bytes FROM user_profiles p
        LEFT JOIN media_objects m ON m.id = p.photo_id AND m.state = 'ACTIVE' WHERE p.account_id = ?
        """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectStorage storage;

    ProfileService(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectStorage storage) {
        this.jdbc = jdbc; this.transactions = transactions; this.storage = storage;
    }

    public Profile get(UUID owner) {
        var rows = jdbc.query(SELECT, this::map, owner);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile not found");
        return rows.getFirst();
    }

    @Transactional
    public Profile save(UUID owner, Profile.Input body, Long version, boolean create) {
        rejectNul(body.displayName(), body.givenName(), body.familyName(), body.bio());
        int changed;
        if (create) {
            changed = jdbc.update("""
                INSERT INTO user_profiles(account_id, display_name, given_name, family_name, bio, birth_date)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, owner, body.displayName().strip(), body.givenName(), body.familyName(), body.bio(), body.birthDate());
        } else {
            changed = jdbc.update("""
                UPDATE user_profiles SET display_name = ?, given_name = ?, family_name = ?, bio = ?, birth_date = ?,
                    version = version + 1, updated_at = now() WHERE account_id = ? AND version = ?
                """, body.displayName().strip(), body.givenName(), body.familyName(), body.bio(), body.birthDate(), owner, version);
        }
        if (changed != 1) throw precondition();
        return get(owner);
    }

    public void checkVersion(UUID owner, long expected) {
        if (get(owner).version() != expected) throw precondition();
    }

    public Profile upload(UUID owner, long version, ImageSanitizer.Image image) {
        checkVersion(owner, version);
        UUID id = UUID.randomUUID();
        String key = "avatars/" + owner + "/" + id + ".jpg";
        // Commit a durable reservation before any external side effect. Stale/failed uploads are collected.
        transactions.executeWithoutResult(status -> jdbc.update("INSERT INTO media_objects(id, owner_id, object_key, sha256, size_bytes, state) VALUES (?, ?, ?, ?, ?, 'PENDING')",
            id, owner, key, image.sha256(), image.bytes().length));
        try {
            storage.put(key, image.bytes());
            return transactions.execute(status -> {
                UUID old = lockPhoto(owner);
                int changed = jdbc.update("UPDATE user_profiles SET photo_id = ?, version = version + 1, updated_at = now() WHERE account_id = ? AND version = ?", id, owner, version);
                if (changed != 1) throw precondition();
                int activated = jdbc.update("UPDATE media_objects SET state = 'ACTIVE' WHERE id = ? AND state = 'PENDING'", id);
                if (activated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Upload reservation expired; retry");
                if (old != null) retire(old);
                return get(owner);
            });
        } catch (RuntimeException failed) {
            transactions.executeWithoutResult(status -> jdbc.update("UPDATE media_objects SET state = 'DELETING', delete_after = now() + interval '1 minute' WHERE id = ? AND state = 'PENDING'", id));
            throw failed;
        }
    }

    public byte[] photo(UUID owner, Profile profile) {
        if (profile.photo() == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Photo not found");
        String key = jdbc.queryForObject("SELECT m.object_key FROM user_profiles p JOIN media_objects m ON p.photo_id = m.id WHERE p.account_id = ? AND m.state = 'ACTIVE'", String.class, owner);
        byte[] bytes = storage.get(key);
        if (!ImageSanitizer.digest(bytes).equals(profile.photo().sha256()))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Photo changed during read; retry");
        return bytes;
    }

    @Transactional
    public Profile removePhoto(UUID owner, long version) {
        Profile current = get(owner);
        checkVersion(owner, version);
        UUID id = lockPhoto(owner);
        int changed = jdbc.update("UPDATE user_profiles SET photo_id = null, version = version + 1, updated_at = now() WHERE account_id = ? AND version = ?", owner, version);
        if (changed != 1) throw precondition();
        if (id != null) retire(id);
        return get(owner);
    }

    @Transactional
    public void delete(UUID owner, long version) {
        UUID photo = lockPhoto(owner);
        int changed = jdbc.update("DELETE FROM user_profiles WHERE account_id = ? AND version = ?", owner, version);
        if (changed != 1) throw precondition();
        if (photo != null) retire(photo);
    }

    private UUID lockPhoto(UUID owner) {
        var rows = jdbc.query("SELECT photo_id FROM user_profiles WHERE account_id = ? FOR UPDATE",
            (row, n) -> row.getObject("photo_id", UUID.class), owner);
        if (rows.isEmpty()) throw precondition();
        return rows.getFirst();
    }

    private void retire(UUID id) {
        jdbc.update("UPDATE media_objects SET state = 'DELETING', delete_after = now() + interval '1 minute' WHERE id = ?", id);
    }

    private Profile map(ResultSet row, int index) throws SQLException {
        String hash = row.getString("sha256");
        return new Profile(row.getObject("account_id", UUID.class), row.getString("display_name"), row.getString("given_name"),
            row.getString("family_name"), row.getString("bio"), row.getObject("birth_date", LocalDate.class), row.getLong("version"),
            row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(),
            hash == null ? null : new Profile.Photo(hash, row.getInt("size_bytes"), "image/jpeg", "/api/v1/profile/photo"));
    }

    private static void rejectNul(String... values) {
        for (String value : values) if (value != null && value.indexOf(0) >= 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "NUL characters are not supported");
    }

    static ResponseStatusException precondition() {
        return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Resource version changed; fetch it and retry with the current ETag");
    }
}
