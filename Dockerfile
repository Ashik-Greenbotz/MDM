# Device portal server, self-hosted mode (not Firebase Cloud Functions).
# server.js has no runtime dependencies outside CLOUD mode (K_SERVICE unset here), so no npm install needed.
FROM node:22-slim

WORKDIR /app

COPY functions/server.js functions/mqtt.js functions/maintenance-default.jpg ./functions/
COPY dashboard ./dashboard
COPY agents ./agents

ENV PORT=8080
ENV DATA_DIR=/app/server

RUN mkdir -p /app/server && chown -R node:node /app

USER node
EXPOSE 8080

CMD ["node", "functions/server.js"]
