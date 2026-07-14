FROM node:24-alpine AS build

ARG VITE_APP_VERSION
ENV VITE_APP_VERSION=${VITE_APP_VERSION}

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY index.html tsconfig.json tsconfig.app.json tsconfig.node.json vite.config.ts ./
COPY src/ ./src/
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/ /usr/share/nginx/html/__fleet/

EXPOSE 8080
