# Dragon MCP Gateway

The open-core, hyper-scalable gateway for Model Context Protocol (MCP).

Built on battle-tested distributed systems technology (Apache Pekko), Dragon is designed from the ground up for extreme scale, performance, and reliability. Whether you're running on a single machine or a cluster of hundreds of nodes, Dragon delivers consistent, high-throughput MCP routing with minimal overhead.

## 🚀 Killer Features

### **Actor-Based Architecture DNA**
- **Extreme Scalability**: Built on Apache Pekko (Akka fork), Dragon's actor-based architecture provides natural isolation, fault tolerance, and distribution
- **Cluster-Native**: Horizontally scalable across **hundreds of nodes** with built-in cluster sharding, service discovery, and automatic failover
- **Multi-Tenancy at Scale**: User-scoped actors ensure complete isolation - each user gets their own MCP server instance with custom tool aggregation

### **Async Everything, Maximum Throughput**
- **Non-Blocking by Design**: Fully asynchronous architecture from HTTP handlers to database access
- **High Throughput per Core**: Actor-based concurrency model achieves exceptional throughput without thread overhead
- **Backpressure Support**: Reactive streams with proper backpressure handling for streaming MCP operations
- **Efficient Resource Usage**: Event-driven model means idle connections consume minimal resources

### **Enterprise-Grade Distribution**
- **Cluster Sharding**: Automatic entity distribution across cluster nodes with 100-shard default configuration
- **Location Transparency**: Send messages to actors without knowing which node they're on
- **Self-Healing**: Automatic actor recovery and rebalancing when nodes join/leave the cluster
- **Zero-Downtime Deployments**: Rolling updates supported via cluster-aware coordination

### **Scala Power, Minimal Boilerplate**
- **Scala 3.7.3**: Modern, concise syntax with powerful type system
- **Expressive Code**: Pattern matching, for-comprehensions, and functional paradigms reduce boilerplate
- **Type Safety**: Catch errors at compile time, not runtime
- **JVM Performance**: Production-ready performance with GraalVM compatibility

### **Production Ready**
- 🧠 **Self-Hostable**: Deploy on your infrastructure with full control
- 🌐 **gRPC + HTTP APIs**: Multi-protocol support for management and MCP operations
- 🔒 **Secure by Design**: Database-backed configuration, prepared for multi-tenant auth
- 📊 **Observable**: Comprehensive logging, metrics ready (Prometheus compatible)
- 🗄️ **Database-Backed**: PostgreSQL storage with Flyway migrations
- ⚖️ **Apache 2.0 Licensed**: Open-source with commercial support available

## 🎯 What is Dragon?

Dragon is a distributed MCP (Model Context Protocol) gateway that aggregates tools from multiple downstream MCP servers and exposes them through a unified, user-scoped API. It acts as an intelligent routing layer between AI applications and tool providers.

**Key Capabilities:**
- **Tool Aggregation**: Combine tools from multiple MCP servers into a single namespace
- **Session-Based Routing**: Route requests to user-specific MCP server instances
- **Dynamic Tool Discovery**: Automatically fetch and namespace tools from registered servers
- **Streaming Support**: Full support for Server-Sent Events (SSE) and streaming progress
- **Resource & Prompt Support**: Beyond tools - handle MCP resources and prompts

## 🏗️ Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                     Dragon MCP Gateway                       │
│                    (Pekko Cluster Layer)                     │
├─────────────────────────────────────────────────────────────┤
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ McpServerActor│  │ McpServerActor│  │ McpServerActor│     │
│  │  (user-123)   │  │  (user-456)   │  │  (user-789)   │     │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
│         │                  │                  │              │
│         └──────────────────┴──────────────────┘              │
│                            │                                 │
│  ┌─────────────────────────▼──────────────────────────┐     │
│  │          Tool Aggregation Layer                     │     │
│  │  • Default Tools (echo, system_info, etc.)         │     │
│  │  • Namespaced Downstream Tools                     │     │
│  └─────────────────────────────────────────────────────┘     │
│                            │                                 │
│  ┌─────────────────────────▼──────────────────────────┐     │
│  │         McpClientActor Pool                         │     │
│  │  Connections to downstream MCP servers              │     │
│  └─────────────────────────────────────────────────────┘     │
└─────────────────────────────────────────────────────────────┘
                             │
        ┌────────────────────┼────────────────────┐
        ▼                    ▼                    ▼
   MCP Server A         MCP Server B         MCP Server C
```

**Distributed Components:**
- **TurnstileMcpGateway**: Pekko HTTP gateway with header-based routing
- **McpServerActor**: User-scoped MCP server instances (sharded across cluster)
- **McpClientActor**: Downstream server connections (sharded, auto-initialized)
- **ToolsService**: Per-user tool aggregation from multiple sources
- **Database Layer**: PostgreSQL with Slick ORM and Flyway migrations

## 🎉 First Release - v1.0.0

This is the inaugural release of Dragon MCP Gateway! We're excited to share this production-ready foundation with the community. Built on years of distributed systems experience and modern Scala best practices, Dragon represents a new approach to MCP infrastructure.

**What's Included:**
- ✅ Complete MCP protocol implementation (tools, resources, prompts)
- ✅ Cluster-aware actor system with sharding
- ✅ gRPC API for server management
- ✅ HTTP/SSE streaming support
- ✅ PostgreSQL-backed configuration
- ✅ Spring WebFlux ↔ Pekko HTTP adapters
- ✅ Comprehensive logging and error handling
- ✅ Production-ready deployment configuration

## 🚦 Quick Start

### Prerequisites
- JDK 11 or later
- sbt 1.9+
- PostgreSQL 12+

### Running Locally

```bash
# Clone the repository
git clone https://github.com/portola-labs/dragon.git
cd dragon/scala/turnstile

# Configure database (update application.conf or use environment variables)
export DEPLOYMENT_NAME=default

# Run database migrations
sbt run  # Migrations run automatically on startup

# Start the gateway
sbt run
```

The gateway will start with:
- **gRPC API**: `localhost:8080` (server management)
- **MCP Gateway**: `localhost:8081/mcp` (MCP protocol endpoint)
- **Cluster Management**: `localhost:8558` (Pekko management)

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

## 📚 Documentation

- **[Architecture Guide](scala/turnstile/CLAUDE.md)**: Deep dive into system design
- **[Configuration Reference](scala/turnstile/src/main/resources/application.conf)**: All configuration options
- **[Database Schema](scala/turnstile/src/main/resources/db/migration/)**: Flyway migrations
- **[API Documentation](scala/turnstile/src/main/protobuf/)**: gRPC protocol definitions

## 🛠️ Technology Stack

- **Apache Pekko 1.2.1**: Actor system, clustering, HTTP, gRPC
- **Scala 3.7.3**: Modern, type-safe language
- **PostgreSQL**: Reliable persistent storage
- **Slick 3.5.2**: Type-safe database access
- **Flyway**: Database migration management
- **MCP Java SDK 0.14.1**: Official MCP protocol implementation
- **Spring WebFlux**: Reactive HTTP transport (embedded)

## 🌍 Deployment

### Single Node (Development)
```bash
sbt run
```

### Cluster Deployment (Production)

Dragon automatically forms clusters when nodes can discover each other:

```bash
# Node 1
PEKKO_REMOTE_PORT=25520 sbt run

# Node 2
PEKKO_REMOTE_PORT=25521 \
PEKKO_SEED_NODES="pekko://turnstile@localhost:25520" \
sbt run

# Node 3
PEKKO_REMOTE_PORT=25522 \
PEKKO_SEED_NODES="pekko://turnstile@localhost:25520" \
sbt run
```

For Kubernetes deployment, Pekko Cluster Bootstrap with Kubernetes discovery is included.

## 🤝 Contributing

We welcome contributions! This is an open-core project licensed under Apache 2.0.

## 📄 License

Apache License 2.0 - See [LICENSE](LICENSE) for details.

## 💼 Commercial Support

> **Portola Network, Inc.** offers commercial support, enterprise features, and professional services for Dragon MCP Gateway.
>
> Contact us for:
> - Enterprise support and SLAs
> - Custom feature development
> - Architecture consulting
> - Training and onboarding
> - Managed cloud deployment

---

**Built with ❤️ by Portola Network, Inc.**

**Enjoy this first release!** We're excited to see what you build with Dragon.
