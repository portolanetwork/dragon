# Build stage
FROM node:23-alpine AS build

# Set the working directory
WORKDIR /app

# Copy package.json and package-lock.json
COPY package*.json ./

# Install dependencies
RUN npm install

# Copy the rest of the application code
COPY . .

# Build the application
RUN npm run build

# Production stage
FROM nginx:alpine

# Copy the built files from the build stage
COPY --from=build /app/dist /usr/share/nginx/html

# Copy custom Nginx configuration
COPY nginx.conf /etc/nginx/conf.d/default.conf

#COPY src/config/deployment.staging-usw1.jsx /usr/share/nginx/html/deployment.jsx

RUN mkdir -p /usr/src/app

COPY config/deployment.local.jsx /usr/src/app/
COPY config/deployment.staging-usw1.jsx /usr/src/app/
COPY config/deployment.prod-usw1.jsx /usr/src/app/

# Copy the entrypoint script
COPY entrypoint.sh /entrypoint.sh

# Make the entrypoint script executable
RUN chmod +x /entrypoint.sh

# Expose port 5000
EXPOSE 5000

# Start Nginx
#CMD ["nginx", "-g", "daemon off;"]

# Set the entrypoint
ENTRYPOINT ["/entrypoint.sh"]

