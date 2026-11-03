# MVP-095B: the controlled SYNTHETIC payment provider (development stack, browser suite and hr-dev
# only). It is not a bank, not M-Pesa, not Airtel Money, not Orange Money, and moves no money.
FROM node:24.21.0-alpine3.24@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1
RUN apk add --no-cache openssl \
 && mkdir -p /app /tls /shared \
 && chown -R node:node /app /tls /shared
WORKDIR /app
COPY --chown=node:node server.mjs entrypoint.sh /app/
USER node
EXPOSE 8443 8099
ENTRYPOINT ["sh", "/app/entrypoint.sh"]
