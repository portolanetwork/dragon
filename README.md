# Dragon MCP Hub

An open-source, cloud-native, distributed hub for Model Context Protocol (MCP).

While initially supporting MCP spec, Dragon's architecture is protocol-agnostic and built to support emerging connectivity standards as AI ecosystem evolves.

Dragon is built on Apache Pekko's actor system, providing a foundation for scalable MCP routing and tool aggregation. It runs on a single machine or scales horizontally across a cluster.

## What is Dragon?

Dragon is a distributed MCP hub that aggregates tools from multiple downstream MCP servers and exposes them through a unified, user-scoped API. It routes requests between AI applications and tool providers.

## Capabilities

### Current
- **MCP Server Aggregation**: Aggregate multiple MCP servers into one
- **Tenant/User level isolation**: Route requests to user-specific MCP server instances
- **Spec compliant**: Full support for streaming HTTP spec

### Planned
- **Auth**: Full Auth support via third part IDPs
- **Console UI**: Web based console UI
- **Policy Based Enforcement**: Manage tool access via policies
- **Monitoring**: Monitor tool usage via event logging
- **Compliance**: Compliance for enterprise use-cases

## Modern & Extensible Tech Stack

- Extensible in any JVM based languages
- Async programming using Apache Pekko (Akka fork)
- MCP implementation uses official MCP SDK
- gRPC API for server management
- PostgreSQL DB
- Comprehensive logging and error handling


## Key Features (0.1.x)

For the first developer release, Dragon provides a robust foundation for self-hosting and managing MCP connections.

- **Self-Hostable & Distributed**: Run on a single machine or scale horizontally across a cluster using its native Apache Pekko foundation.
- **Protocol Support**: Full support for the Model Context Protocol (MCP) specification (2025-06-18).
- **Connectivity**: gRPC and HTTP APIs for both management and high-throughput MCP operations.
- **Auth**: TBD
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
- JDK 17 or later
- sbt 1.11+
- PostgreSQL 12+

### Running Locally

```bash
# Clone the repository
git clone https://github.com/portola-labs/dragon.git
cd dragon/scala/turnstile

# Create a database (Use neon.dev and updated env vars in .env file)
export DEPLOYMENT_NAME=default
export DATABASE_URL=<db-url>
export DATABASE_PASSWORD=<db-password>


# Start the gateway (migrations run automatically)
sbt "runMain app.dragon.turnstile.main.Turnstile"

# Connect using MCP Inspector with following settings
## Transport Type: Streamable HTTP
## URL: http://127.0.0.1:8082/mcp
## Connection Type: Via Proxy
## Click: Connect
## Click: Tools -> List Tools
## You can create breakpoints to examine how individual example tools are called under: app.dragon.turnstile.service.tools

```

The gateway starts with:
- gRPC API: `localhost:8082` (server management)
- MCP Gateway: `localhost:8082/mcp` (MCP protocol endpoint)

### Register a Downstream MCP Server

Use the gRPC API to register downstream servers:

```bash
# Example using grpcurl
grpcurl -plaintext -d '{
  "name": "my-mcp-server",
  "url": "https://mcp.deepwiki.com/mcp1"
}' localhost:8080 dragon.turnstile.v1.TurnstileService/CreateMcpServer
```

### Connect an MCP Client

Point your MCP client to the gateway. Setup MCP Inspector with following:

- Transport Type: Streamable HTTP
- URL: http://127.0.0.1:8082/mcp
- Connection Type: Via Proxy

Steps:

- Click: Connect
- Click: Tools -> List Tools
- You can create breakpoints to examine how individual example tools are called under: app.dragon.turnstile.service.tools


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
sbt "runMain app.dragon.turnstile.main.Turnstile"
```

### Cluster Deployment (Production)

Documentation coming soon.

## Contributing

Contributions are welcome. This is an open-core project licensed under Apache 2.0.

## License

© 2025 Sami Malik, Portola Networki, Inc. Licensed under the Apache License, Version 2.0 - See [LICENSE](LICENSE) for details.

## Branding and Attribution

While this project is open source under the Apache 2.0 license, the name "Dragon" and related branding are protected trademarks. You may not use the name "Dragon", "Portola" or similar marks for derivative works, forks, or redistributions without written permission.

## Commercial Support

Portola Network, Inc. offers commercial support, enterprise features, and professional services for Dragon MCP Gateway.

## Maintained by

**Sami Malik**  
Founder, [Portola Network, Inc](https://portolanetwork.io)  
📧 sami.malik@portolanetwork.io  
[LinkedIn](https://www.linkedin.com/in/usamah) · [X](https://x.com/samimalik10101)

For questions or contributions, please open an issue or pull request.

---

Built by Portola Network, Inc.
