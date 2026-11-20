# Family Verify relay, for hosting on the internet (Fly.io, Railway, any Docker host).
# Build from the REPO ROOT (it needs vendor/ for the browser test pages):
#   docker build -f mobile/relay/Dockerfile -t family-verify-relay .
#   docker run -p 8000:8000 -e MONITOR_TOKEN=change-me family-verify-relay
FROM node:22-alpine
WORKDIR /app
RUN npm init -y >/dev/null && npm install --omit=dev ws@8.23.0
COPY mobile/relay/server.js mobile/relay/check.js mobile/relay/
COPY mobile/www/ mobile/www/
COPY vendor/ mobile/www/vendor/
ENV NODE_ENV=production PORT=8000
EXPOSE 8000
USER node
CMD ["node", "mobile/relay/server.js"]
