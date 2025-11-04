# Dragon MCP Hub

An open-source, distributed hub for Model Context Protocol (MCP).

While initially supporting MCP spec, Dragon's architecture is protocol-agnostic and built to support emerging connectivity standards as AI ecosystem evolves.

Dragon is built on Apache Pekko's actor system, providing a foundation for scalable MCP routing and tool aggregation. It runs on a single machine or scales horizontally across a cluster.

## What is Dragon?

Dragon is a distributed MCP hub that aggregates tools from multiple downstream MCP servers and exposes them through a unified, user-scoped API. It routes requests between AI applications and tool providers.

## Capabilities

### Current
- **MCP Server Agreegation**: Aggregate multiple MCP servers into one
- **Tenant/User level isolation**: Route requests to user-specific MCP server instances
- **Spec compliant**: Full support for streaming HTTP spec

### Planned
- **Auth**: Full Auth support via third part IDPs
- **Console UI**: Web based console UI
- **Policy Based Enforcement**: Manage tool access via policies
- **Monitoring**: Monitor tool usage via event logging
- **Compliance**: Compliance for enterprise use-cases

## Modern & Extensible Tech Stack

- Extensible in any JVM based languates (Scala prefered)
- Async programming using Apache Pekko (Akka fork)
- MCP implementation uses official MCP SDK
- gRPC API for server management
- PostgreSQL DB
- Comprehensive logging and error handling


## Key Features (0.1.x)

For the first developer release, Dragon provides a robust foundation for self-hosting and managing MCP connections.

- **Self-Hostable & Distributed**: Run on a single machine or scale horizontally across a cluster using its native Apache Pekko (Akka fork) foundation.
- **Protocol Support**: Full support for the Model Context Protocol (MCP) specification (2025-06-18).
- **Connectivity**: gRPC and HTTP APIs for both management and high-throughput MCP operations.
- **Observability**: TBD
- **Reliable Persistence**: PostgreSQL-backed configuration management using Flyway for database migrations.
- **Open Source**: Released under the permissive Apache 2.0 license.

## Why Dragon?

Dragon is engineered to address the demanding requirements of AI infrastructure with an emphasis on scalability, resilience, and high throughput.

### Built for Scale and Resilience

Dragon is founded on the principles of the Reactive Manifesto and built atop Apache Pekko.

- **Actor-Based Architecture**: Uses Apache Pekko for natural isolation, fault tolerance, and distribution (including automatic recovery and rebalancing).
- **Horizontally Scalable**: Easily scales across multiple nodes with cluster sharding and service discovery.
- **Asynchronous Design**: A fully asynchronous, event-driven model from HTTP to database access, enabling high throughput and minimizing resource usage for idle connections.
- **Reactive Streaming**: Implements reactive streams with backpressure handling for stable, streaming MCP operations.

### Modern & Extensible Foundation

- **Protocol-Agnostic Core**: The architecture is designed for rapid iteration and easy extension to new protocols and connectivity standards beyond MCP.
- **Scala 3**: Written in modern Scala 3 with minimal boilerplate, leveraging its strong type system to catch errors at compile time and running reliably on the JVM.


## Getting Started

### Prerequisites
- JDK 11 or later
- sbt 1.9+
- PostgreSQL 12+

### Running Locally

```bash
# Clone the repository
git clone https://github.com/portola-labs/dragon.git
cd dragon/scala/turnstile

# Create a database (Use neon.dev and updated env vars in .env file)
export DEPLOYMENT_NAME=default

# Start the gateway (migrations run automatically)
sbt run
```

The gateway starts with:
- gRPC API: `localhost:8080` (server management)
- MCP Gateway: `localhost:8081/mcp` (MCP protocol endpoint)
- Cluster Management: `localhost:8558` (Pekko management)

### Register a Downstream MCP Server

Use the gRPC API to register downstream servers:

```bash
# Example using grpcurl
grpcurl -plaintext -d '{
  "name": "my-mcp-server",
  "url": "http://localhost:9000"
}' localhost:8080 dragon.turnstile.api.v1.TurnstileService/CreateMcpServer
```

### Connect an MCP Client

Point your MCP client to the gateway:

```bash
# Example: Claude Desktop config
{
  "mcpServers": {
    "dragon-gateway": {
      "url": "http://localhost:8081/mcp"
    }
  }
}
```

## Documentation

- [Configuration Reference](scala/turnstile/src/main/resources/application.conf) - All configuration options
- [Database Schema](scala/turnstile/src/main/resources/db/migration/) - Flyway migrations
- [API Documentation](scala/turnstile/src/main/protobuf/) - gRPC protocol definitions

## Technology Stack

- Apache Pekko 1.2.1 - Actor system, clustering, HTTP, gRPC
- Scala 3.7.3 - Language
- PostgreSQL - Persistent storage
- Slick 3.5.2 - Database access
- Flyway - Database migrations
- MCP Java SDK 0.14.1 - MCP protocol implementation
- Spring WebFlux - Reactive HTTP transport (embedded)

## Deployment

### Single Node (Development)

```bash
sbt run
```

### Cluster Deployment (Production)

Documentation coming soon.

## Contributing

Contributions are welcome. This is an open-core project licensed under Apache 2.0.

## License

Apache License 2.0 - See [LICENSE](LICENSE) for details.

## Branding and Attribution

While this project is open source under the Apache 2.0 license, the name "Dragon" and related branding are protected trademarks. You may not use the name "Dragon", "Portola" or similar marks for derivative works, forks, or redistributions without written permission.

## Commercial Support

Portola Network, Inc. offers commercial support, enterprise features, and professional services for Dragon MCP Gateway.

---

Built by Portola Network, Inc.
