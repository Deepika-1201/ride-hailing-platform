# syntax=docker/dockerfile:1.7
# The demo web app (ADR-022), served by nginx beside the platform it proxies.
FROM node:22-alpine AS build
WORKDIR /web
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.29-alpine
ENV PLATFORM_URL=http://app:8080
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /web/dist /usr/share/nginx/html
