#!/bin/sh

# Ensure DEPLOYMENT_NAME is set
if [ -z "$DEPLOYMENT_NAME" ]; then
  echo "ERROR: DEPLOYMENT_NAME environment variable is not set."
  exit 1
fi

# Check if DEPLOYMENT_NAME is set, otherwise use a default value
DEPLOYMENT_NAME=${DEPLOYMENT_NAME:-"local"}

# Check if the file exists before copying
if [ ! -f "/usr/src/app/deployment.$DEPLOYMENT_NAME.jsx" ]; then
    echo "ERROR: File /usr/src/app/deployment.$DEPLOYMENT_NAME.jsx does not exist."
    exit 1
fi

cp /usr/src/app/deployment.$DEPLOYMENT_NAME.jsx /usr/share/nginx/html/deployment.jsx

# Echo the contents of the copied file
cat /usr/share/nginx/html/deployment.jsx

# Start Nginx
nginx -g "daemon off;"
