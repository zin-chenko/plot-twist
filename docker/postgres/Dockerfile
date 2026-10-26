FROM postgres:17
COPY bootstrap.sh roles.sql /bootstrap/
# Existing Windows clones may still contain CRLF despite .gitattributes.
RUN sed -i 's/\r$//' /bootstrap/bootstrap.sh /bootstrap/roles.sql
CMD ["sh", "/bootstrap/bootstrap.sh"]
