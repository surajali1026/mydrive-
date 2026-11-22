FROM node:22-alpine

WORKDIR /app

COPY backend/package*.json ./
RUN npm install --omit=dev=false

COPY backend/ ./
RUN npm run build

ENV NODE_ENV=production
EXPOSE 4000
CMD ["npm", "start"]
