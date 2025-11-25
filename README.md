# Dragon MCP Gateway

**Dragon is a cloud-native, distributed gateway for the Model Context Protocol (MCP), unifying multiple downstream MCP servers into a single, user-scoped API for AI applications.** 

Dragon serves as the central control plane between AI applications (like Claude, ChatGPT, etc.) and your internal or external tool providers. While initially supporting the MCP spec, its core architecture is protocol-agnostic, built to support emerging connectivity standards as the AI ecosystem evolves.

---

## Key Use Cases / Why Dragon?

* **Simplify Integration:** Provide a single MCP endpoint to AI applications and manage all your tool/data access patterns centrally.
* **Prevent Data Leaks:** Implement fine-grained Data Loss Prevention (DLP) controls to ensure models only access approved data.
* **Gain Visibility:** Utilize comprehensive usage monitoring for auditing, debugging, and identifying patterns or misuse by AI agents.
* **Ensure Compliance:** Centralize enterprise compliance controls (e.g., GDPR, HIPAA) across all model-accessed data.
* **Optimize Token Usage:** Implement advanced optimizations, such as progressive tool discovery, to mitigate context bloat and control costs.
* **Extend Functionality:** Use the integrated framework to easily implement and manage your own custom tool providers.

---

## Capabilities

### Current
* **MCP Server Aggregation**: Aggregate multiple downstream MCP servers into one unified gateway.
* **Tenant/User Isolation**: Route requests to user-specific MCP server instances for multi-tenancy.
* **Spec Compliant**: Full support for the streaming HTTP MCP specification (2025-06-18).
* **Auth**: OAuth2/OIDC authentication via third-party IDPs (e.g., Auth0) with JWT validation.
* **Console UI**: Web-based administrative console.
* **Monitoring**: Comprehensive audit logging and event tracking for tool executions and system events.

### Planned
* **Policy Based Enforcement**: Manage tool access and permissions via declarative policies.
* **Compliance Module**: Built-in modules for enterprise-specific compliance mandates.
* **Lazy Loading**: Lazy loading of tool specifications to mitigate context bloat and improve latency.
* **No-Code Datasource Integration**: Ability to create custom tools/datasources directly from APIs without writing code.

---

## Tech Stack

Dragon is built on a modern, robust, and asynchronous foundation, prioritizing resilience and speed.

* **Core Framework:** **Apache Pekko** (Akka fork) actor system for asynchronous, event-driven computing.
* **Language:** **Scala 3** (Extensible in any JVM-based language like Java/Kotlin).
* **Persistence:** **PostgreSQL** database with configuration management via **Flyway** migrations.
* **APIs:** **gRPC** (for server management) and **HTTP** (for high-throughput MCP operations).
* **Protocol Support:** Built on the official Model Context Protocol (MCP) SDK.

---

## Built for Scale and Resilience (Reactive Principles)

Dragon is founded on the principles of the **Reactive Manifesto**, providing an inherently distributed and resilient architecture.

* **Actor-Based Architecture:** Uses Apache Pekko for natural isolation, fault tolerance (automatic recovery and rebalancing), and distribution.
* **Horizontally Scalable:** Easily scales across multiple nodes with native support for cluster sharding and service discovery.
* **Asynchronous Design:** A fully asynchronous, event-driven model from API to database access, enabling high throughput and minimal resource usage.
* **Reactive Streaming:** Implements reactive streams with backpressure handling for stable, high-performance MCP streaming operations.
* **Self-Hostable & Distributed:** Run on a single machine or scale horizontally across a cluster using its native Apache Pekko foundation.
* **Protocol-Agnostic Core:** The architecture is designed for rapid extension to new protocols and connectivity standards beyond MCP.
* **Open Source:** Released under the permissive **Apache 2.0 license**.
---


## Architecture 

Documentation coming soon.

## Getting Started

### Prerequisites
- JDK 17 or later
- sbt 1.11+
- PostgreSQL 12+

### Running Locally

NOTE: This setup doc is work-in-progress. I will be updating this to be more comprehensive in the future releases. If you'd like help setting this up, reach out to me at: sami.malik@portolanetwork.io 


```bash
# Clone the repository
git clone https://github.com/portola-labs/dragon.git
cd dragon/scala/turnstile

# Create a database (Use neon.dev and updated env vars in .env file)
export DEPLOYMENT_NAME=default
export DATABASE_URL=<db-url>
export DATABASE_USER=<db-user>
export DATABASE_PASSWORD=<db-password>

# Configure Auth0 (see scala/turnstile/docs/auth-with-auth0.md for setup)
export AUTH0_DOMAIN=<auth0-domain>
export AUTH0_AUDIENCE=<auth0-audience>
export AUTH0_CLIENT_AUDIENCE=<auth0-client-audience> # Auth0 requires this to issue refresh-tokens

# Start the gateway (migrations run automatically)
sbt "runMain app.dragon.turnstile.main.Turnstile"

# Start UI
cd dragon/js/console
npm install
npm run dev

# Navigate to localhost:5173 and login.


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
- grpc-web API: ``localhost:8081`` (server management from UI)
- MCP Gateway: `localhost:8082/mcp` (MCP protocol endpoint)

### Register a Downstream MCP Server

Use the gRPC API to register downstream servers:

```bash
# Example using grpcurl (with authentication)
grpcurl -plaintext \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{
    "user_id": "user-123",
    "name": "my-mcp-server",
    "url": "https://mcp.deepwiki.com/mcp1",
    "auth_type": "discover"
  }' localhost:8080 dragon.turnstile.v1.TurnstileService/CreateMcpServer

# For servers requiring OAuth, initiate login:
# GET http://localhost:8081/login?uuid=<server-uuid>
```


## Documentation

- [Configuration Reference](scala/turnstile/src/main/resources/application.conf) - All configuration options
- [Auth0 Setup Guide](scala/turnstile/docs/auth-with-auth0.md) - TBD
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
- JWT/Auth0 - OAuth2/OIDC authentication and token validation

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

© 2025 Sami Malik, Portola Network, Inc. Licensed under the Apache License, Version 2.0 - See [LICENSE](LICENSE) for details.

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
