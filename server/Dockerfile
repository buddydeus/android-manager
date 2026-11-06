ARG NODE_IMAGE=public.ecr.aws/docker/library/node:24.22.2-bookworm-slim@sha256:01d566e8d94d8c619591d78652babfccd3c6992a3dffa9ed5ab39e11cf6c70e9
FROM ${NODE_IMAGE} AS build
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends python3 make g++ && rm -rf /var/lib/apt/lists/*
COPY package.json package-lock.json ./
RUN npm ci
COPY tsconfig.json tsconfig.api.json ./
COPY api ./api
COPY contracts ./contracts
COPY web ./web
RUN npm run build && npm prune --omit=dev --ignore-scripts

FROM ${NODE_IMAGE} AS apk-tools
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates unzip && rm -rf /var/lib/apt/lists/*
RUN curl --fail --location --retry 3 https://dl.google.com/android/repository/build-tools_r36_linux.zip -o /tmp/tools.zip \
    && echo '5d9ac77fb6ff43d9da518a337b4fcf8f9097113df531d99ccefe80ef7ce8250b  /tmp/tools.zip' | sha256sum --check \
    && unzip -q /tmp/tools.zip -d /opt/tools

FROM ${NODE_IMAGE} AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends openjdk-17-jre-headless ca-certificates libstdc++6 zlib1g && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build --chown=node:node /app/package.json /app/package-lock.json ./
COPY --from=build --chown=node:node /app/node_modules ./node_modules
COPY --from=build --chown=node:node /app/dist ./dist
COPY --from=apk-tools /opt/tools/android-16/aapt2 /opt/android-sdk/build-tools/36.0.0/aapt2
COPY --from=apk-tools /opt/tools/android-16/apksigner /opt/android-sdk/build-tools/36.0.0/apksigner
COPY --from=apk-tools /opt/tools/android-16/lib/apksigner.jar /opt/android-sdk/build-tools/36.0.0/lib/apksigner.jar
RUN mkdir /data && chown node:node /data && chmod 700 /data
USER node
ENV NODE_ENV=production AMS_DATA_DIR=/data AMS_ORIGIN=https://ams.opt.work AMS_BUILD_TOOLS=/opt/android-sdk/build-tools/36.0.0
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 CMD node -e "fetch('http://127.0.0.1:8080/health/ready',{signal:AbortSignal.timeout(3000)}).then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node","dist/api/src/main.js"]
