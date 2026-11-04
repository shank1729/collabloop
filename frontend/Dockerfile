FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY public ./public
COPY src ./src
ARG REACT_APP_API_BASE_URL=/api
ARG REACT_APP_WS_URL
ENV REACT_APP_API_BASE_URL=$REACT_APP_API_BASE_URL
ENV REACT_APP_WS_URL=$REACT_APP_WS_URL
ENV CI=true
ENV GENERATE_SOURCEMAP=false
RUN test -n "$REACT_APP_WS_URL" && npm run build

FROM caddy:2-alpine
COPY --from=build /app/build /srv
# Compose mounts deploy/oracle/Caddyfile and persistent certificate volumes.
EXPOSE 80 443
