# MVP-111: the controlled SYNTHETIC Meta Graph API provider (development stack and hr-dev only).
FROM node:24.21.0-alpine3.24@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1
RUN apk add --no-cache openssl \
 && mkdir -p /app /tls /shared \
 && chown -R node:node /app /tls /shared
WORKDIR /app
COPY --chown=node:node server.mjs entrypoint.sh /app/
USER node
EXPOSE 8443 8099
ENTRYPOINT ["sh", "/app/entrypoint.sh"]
