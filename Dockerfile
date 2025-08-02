# Multi-stage build for Tibia Bot (ARM/AMD64 compatible)
FROM --platform=$BUILDPLATFORM eclipse-temurin:11-jdk as builder

# Set build arguments
ARG TARGETPLATFORM
ARG BUILDPLATFORM

# Install SBT with platform detection
RUN apt-get update && \
    apt-get install -y curl wget gnupg2 && \
    # Add SBT repository
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian /" | tee /etc/apt/sources.list.d/sbt_old.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | gpg --no-default-keyring --keyring gnupg-ring:/etc/apt/trusted.gpg.d/scalasbt-release.gpg --import && \
    chmod 644 /etc/apt/trusted.gpg.d/scalasbt-release.gpg && \
    apt-get update && \
    apt-get install -y sbt && \
    rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /app

# Copy project files
COPY tibia-bot/project/ tibia-bot/project/
COPY tibia-bot/build.sbt tibia-bot/
COPY tibia-bot/src/ tibia-bot/src/

# Build the application
WORKDIR /app/tibia-bot
RUN sbt clean compile stage

# Runtime stage
FROM eclipse-temurin:11-jre

# Create app user
RUN groupadd -r app && useradd -r -g app app

# Set working directory
WORKDIR /app

# Copy the built application from builder stage
COPY --from=builder /app/tibia-bot/target/universal/stage/ .

# Change ownership to app user
RUN chown -R app:app /app

# Switch to app user
USER app

# Expose port 443 (as defined in build.sbt)
EXPOSE 443

# Accept build arguments for PostgreSQL configuration
ARG POSTGRES_HOST=localhost
ARG POSTGRES_PORT=5432
ARG POSTGRES_DB=tibiabot
ARG POSTGRES_USER=postgres
ARG POSTGRES_PASSWORD=""
ARG TOKEN=""

# Set environment variables from build args
ENV POSTGRES_HOST=${POSTGRES_HOST}
ENV POSTGRES_PORT=${POSTGRES_PORT}
ENV POSTGRES_DB=${POSTGRES_DB}
ENV POSTGRES_USER=${POSTGRES_USER}
ENV POSTGRES_PASSWORD=${POSTGRES_PASSWORD}
ENV TOKEN=${TOKEN}

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD pgrep -f "violent-bot-dedicated" || exit 1

# Run the application
CMD ["./bin/violent-bot-dedicated"]