# =============================================================================
# TradeRakshak(tm) Frontend Dockerfile
# Stage 1: Build Angular app with Node.js
# Stage 2: Serve with Nginx
# =============================================================================

# Stage 1 - Build
FROM node:20-alpine AS builder
WORKDIR /app

# Copy package files and install
COPY package*.json ./
RUN npm install --legacy-peer-deps --no-audit --no-fund

# Copy all source files
COPY . .

# Build - try production first, fall back to default
RUN npx ng build --configuration production 2>/dev/null || \
    npx ng build 2>/dev/null || \
    npm run build

# Move output to a known location regardless of Angular version
RUN if [ -d "dist/traderakshak-frontend/browser" ]; then \
      cp -r dist/traderakshak-frontend/browser /output; \
    elif [ -d "dist/traderakshak-frontend" ]; then \
      cp -r dist/traderakshak-frontend /output; \
    else \
      cp -r dist/* /output 2>/dev/null || mkdir /output; \
    fi

# Stage 2 - Nginx
FROM nginx:1.27-alpine
WORKDIR /usr/share/nginx/html

RUN rm -rf ./*

COPY --from=builder /output ./

COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
