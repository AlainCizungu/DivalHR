# DivalHR web (PWA) image. Build context: repository root. Base images pinned by version and digest.
FROM node:26.10.0-alpine3.24@sha256:0b36e8c136b94cd4fcf02188228e76c31ad5872eef3fec8cbd2eee500cfd9e80 AS build
RUN corepack enable && corepack prepare pnpm@10.34.6 --activate
WORKDIR /repo
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml .npmrc ./
COPY packages ./packages
COPY apps/web/package.json ./apps/web/package.json
RUN pnpm install --frozen-lockfile --filter @divalhr/web...
COPY apps/web ./apps/web
RUN pnpm --filter @divalhr/web run build

FROM nginxinc/nginx-unprivileged:1.31.6-alpine3.24@sha256:26b0bf6fbf07297983cb341998d79c831508787de26627dd2a112321b9c3a4af
COPY --from=build /repo/apps/web/dist /usr/share/nginx/html
COPY apps/web/docker/default.conf.template /etc/nginx/templates/default.conf.template
COPY --chmod=0755 apps/web/docker/40-divalhr-config.sh /docker-entrypoint.d/40-divalhr-config.sh
USER 101
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=6 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
