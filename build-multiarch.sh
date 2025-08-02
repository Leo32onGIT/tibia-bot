#!/bin/bash

# Multi-architecture build script for Tibia Bot
# Supports AMD64 and ARM64 platforms

set -e

echo "🚀 Building multi-architecture Docker image for Tibia Bot..."

# Check if buildx is available
if ! docker buildx version > /dev/null 2>&1; then
    echo "❌ Docker buildx is not available. Please install Docker Desktop or enable buildx."
    exit 1
fi

# Create builder instance if it doesn't exist
BUILDER_NAME="tibia-bot-builder"
if ! docker buildx ls | grep -q "$BUILDER_NAME"; then
    echo "📦 Creating new buildx builder instance..."
    docker buildx create --name "$BUILDER_NAME" --driver docker-container --bootstrap
fi

# Use the builder
docker buildx use "$BUILDER_NAME"

# Get image name and tag
IMAGE_NAME=${1:-"tibia-bot"}
TAG=${2:-"latest"}
FULL_IMAGE_NAME="$IMAGE_NAME:$TAG"

echo "🏗️  Building image: $FULL_IMAGE_NAME"
echo "🎯 Target platforms: linux/amd64, linux/arm64"

# Build and push (or load locally)
if [ "$3" = "--push" ]; then
    echo "📤 Building and pushing to registry..."
    docker buildx build \
        --platform linux/amd64,linux/arm64 \
        --tag "$FULL_IMAGE_NAME" \
        --push \
        .
else
    echo "💾 Building and loading locally..."
    # Note: Multi-platform local load requires separate builds
    echo "Building for AMD64..."
    docker buildx build \
        --platform linux/amd64 \
        --tag "${IMAGE_NAME}:${TAG}-amd64" \
        --load \
        .
    
    echo "Building for ARM64..."
    docker buildx build \
        --platform linux/arm64 \
        --tag "${IMAGE_NAME}:${TAG}-arm64" \
        --load \
        .
    
    echo "✅ Multi-arch images built successfully!"
    echo "   - ${IMAGE_NAME}:${TAG}-amd64"
    echo "   - ${IMAGE_NAME}:${TAG}-arm64"
fi

echo "🎉 Multi-architecture build completed!"