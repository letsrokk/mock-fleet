FROM node:26-alpine AS build

ARG VITE_APP_VERSION
ENV VITE_APP_VERSION=${VITE_APP_VERSION}

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY index.html tsconfig.json tsconfig.app.json tsconfig.node.json vite.config.ts ./
COPY public/ ./public/
COPY src/ ./src/
RUN npm run build

FROM nginx:1.30.7-alpine-slim

RUN sed -i '/^user /d; s|/run/nginx.pid|/tmp/nginx.pid|' /etc/nginx/nginx.conf \
    && chown -R 1001:1001 /var/cache/nginx

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/ /usr/share/nginx/html/__fleet/

USER 1001:1001

EXPOSE 8080
