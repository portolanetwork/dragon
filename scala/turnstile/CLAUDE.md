# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Style
- Do not generate any code that is not called. Only generate functions that are needed to do the job. Remove unused code.
- The returns must follow this pattern for error handling purposes:
  - def createSomething(userId: String, Something): Future[Either[ToolsService.ToolsServiceError, Something]]  // If the function internally makes calls that return Future
  - def createSomething(userId: String, Something): Either[ToolsService.ToolsServiceError, Something] // Otherwise

- Use pattern matching and for {} where appropriate
- Put all docs under claude-docs directory



## Project Overview

Turnstile is a Scala 3.7.3 project built on Apache Pekko (formerly Akka), implementing a gRPC service with cluster support. It's a distributed actor-based system with cluster sharding capabilities.

## Build System

The project uses sbt with custom build configuration organized across multiple files in `project/`:
- `Dependencies.scala`: Centralized dependency management with version constants
- `TurnstileBuild.scala`: Custom Scala compiler options
- `BuildInfo.scala`: Build metadata including git commit information
- `JavaAgents.scala`: JVM agent configuration (JMX Prometheus, Kanela)
- `DockerConfig.scala`: Docker image configuration and Dockerfile generation

### Essential Commands

```bash
# Start sbt shell (recommended for development)
sbt

# Inside sbt shell:
compile              # Compile the project
test                 # Run all tests
testOnly <ClassName> # Run a specific test class
run                  # Run the application (AkkaTurnstile main)
clean                # Clean build artifacts
reload               # Reload sbt configuration after changes to build files
Docker/publishLocal  # Build Docker image locally
Docker/publish       # Push Docker image to registry
```

```bash
# Outside sbt shell:
sbt compile          # Compile the project
sbt test             # Run all tests
sbt "testOnly app.dragon.turnstile.GreeterServiceImplSpec"  # Run specific test
sbt run              # Run the application
sbt Docker/publishLocal  # Build Docker image
```

### gRPC Code Generation

The project uses `pekko-grpc-plugin` for code generation from protobuf files:
- Protobuf definitions: `src/main/protobuf/`
- Generated code appears in `target/scala-3.7.3/pekko-grpc/main/`
- Configuration: `pekkoGrpcGeneratedSources := Seq(PekkoGrpc.Server, PekkoGrpc.Client)`
- Server power APIs are enabled: `pekkoGrpcCodeGeneratorSettings += "server_power_apis"`

After modifying `.proto` files, run `sbt compile` to regenerate gRPC code.

## Architecture

### Package Structure

The project follows a layered architecture:
- **controller/**: Application entry points and coordinators
  - `AkkaTurnstile.scala`: Main application entry point
  - `Guardian.scala`: Root supervisor actor
  - `GreeterServer.scala`: gRPC server binding and lifecycle
- **service/**: Business logic and service implementations
  - `GreeterServiceImpl.scala`: gRPC service implementation
- **client/**: Client implementations
  - `GreeterClient.scala`: gRPC client
- **config/**: Configuration management
  - `ApplicationConfig.scala`: Environment-based configuration loader
- **mcp/**: Model Context Protocol server
  - `TurnstileMcpServer.scala`: MCP server implementation
- **actor/**: Actor-related utilities and traits
  - `TurnstileSerializable.scala`: Marker trait for serialization

### Application Lifecycle

Entry point: `controller/AkkaTurnstile.scala` (extends App)
1. Creates ActorSystem with Guardian as root actor
2. Starts PekkoManagement for cluster management
3. Starts ClusterBootstrap for automatic cluster formation
4. Guardian spawns GreeterServer with configured host/port
5. Guardian starts MCP server if enabled in configuration

### Configuration System

`config/ApplicationConfig.scala` manages environment-based configuration:
- Uses `DEPLOYMENT_NAME` env var to select config file
- Supported environments: ci, staging, production, default
- Config files: `application.conf` (default), `ci.conf`, `staging.conf`, `production.conf`
- All configs should follow the structure in `application.conf`

Key configuration sections:
- `turnstile.grpc`: gRPC server settings (host, port)
- `turnstile.mcp`: Model Context Protocol server settings (enabled, host, port, name, version)
- `turnstile.cluster`: Cluster-specific settings (num-shards)
- `pekko.remote.artery`: Remoting configuration
- `pekko.cluster`: Cluster settings including seed nodes
- `pekko.management`: Management HTTP endpoint
- `pekko.discovery`: Service discovery configuration

### Actor System Design

The project uses Pekko Typed actors:
- **Guardian**: Root supervisor that initializes the system
- **Cluster Sharding**: Configured for distributed entity management (100 shards default)
- **Serialization**: Jackson JSON for custom types via `TurnstileSerializable` marker trait

To add new sharded actors:
1. Create actor behavior extending appropriate typed behavior
2. Initialize sharding in Guardian (see commented example in `controller/Guardian.scala:25-27`)
3. Mark serializable messages with `TurnstileSerializable` trait
4. Update `application.conf` serialization-bindings if needed

### gRPC Service Implementation

Services are defined in protobuf and implemented in Scala:
- **Proto definition**: `src/main/protobuf/helloworld.proto`
- **Implementation**: `service/GreeterServiceImpl.scala`
- **Server binding**: `controller/GreeterServer.scala` handles HTTP/2 binding with server reflection

The GreeterServer demonstrates:
- Request-reply pattern: `sayHello`
- Bidirectional streaming: `sayHelloToAll` using MergeHub/BroadcastHub

When adding new gRPC services:
1. Define service in `.proto` file in `src/main/protobuf/`
2. Run `sbt compile` to generate interfaces
3. Implement generated trait in `service/` package
4. Register handler in `controller/GreeterServer.scala` using `ServiceHandler.concatOrNotFound`
5. Add to reflection list if needed

## Testing

Test structure:
- Location: `src/test/scala/app/dragon/turnstile/`
- Framework: ScalaTest 3.2.19
- Mocking: ScalaMock 6.1.1
- Pekko testkit: `pekko-actor-testkit-typed` for actor testing

Run individual test classes:
```bash
sbt "testOnly app.dragon.turnstile.GreeterServiceImplSpec"
```

## Key Dependencies

- **Pekko 1.2.1**: Core actor system, clustering, persistence, streams
- **Pekko HTTP 1.2.0**: HTTP/2 support for gRPC
- **Pekko gRPC 1.1.1**: gRPC code generation and runtime
- **Pekko Management 1.1.1**: Cluster management, bootstrap, Kubernetes discovery
- **MCP Java SDK 0.14.1**: Model Context Protocol server implementation
- **Scala 3.7.3**: Latest Scala 3 with strict compiler options

## Important Notes

- The project uses Pekko (Apache fork of Akka) not Akka
- Scala 3.7.3 syntax and features are available
- Java serialization is enabled for compatibility but Jackson JSON is preferred
- The application uses coordinated shutdown with 10s hard termination deadline
- BuildInfo generates `app.dragon.turnstile.build.BuildInfo` with project metadata

## Cluster Configuration

Default local cluster setup:
- Actor system name: `turnstile`
- Remoting port: 25520
- Management HTTP: 8558
- gRPC server: 8080
- MCP server: 8081
- Seed nodes configured for single-node local cluster

For multi-node clusters, update:
- `pekko.remote.artery.canonical` (hostname, port)
- `pekko.cluster.seed-nodes`
- `pekko.discovery.config.services.turnstile.endpoints`

## Model Context Protocol (MCP) Server

The project includes an MCP server implementation for exposing tools, resources, and prompts via the Model Context Protocol over HTTP streaming.

### MCP Components

- **Location**: `src/main/scala/app/dragon/turnstile/mcp/TurnstileMcpServer.scala`
- **Status**: Skeleton implementation with comprehensive documentation
- **Integration**: Started by Guardian during application initialization
- **Configuration**: `turnstile.mcp` section in application.conf

### MCP Server Features (Planned)

**Tools:**
- `echo`: Message echo for testing
- `system_info`: Server system information
- `calculate`: Basic arithmetic operations

**Resources:**
- `turnstile://status`: Current server status
- `turnstile://config`: Server configuration
- `turnstile://metrics`: Runtime metrics

**Prompts:**
- `greeting`: Generate greeting messages
- `system_analysis`: System analysis prompt templates

### Configuration

```hocon
turnstile.mcp {
  enabled = true                     # Enable/disable MCP server
  name = "Turnstile MCP Server"     # Server name
  version = "1.0.0"                 # Server version
  host = "0.0.0.0"                  # Bind address
  port = 8081                       # HTTP port for MCP endpoint
}
```

### Completing the MCP Implementation

The current implementation is a skeleton. To complete it:

1. Import classes from `io.modelcontextprotocol.spec` package
2. Implement HTTP streaming transport using `McpServerTransport`
3. Create `McpServerSession` with server info and capabilities
4. Register tool handlers with `McpSchema.Tool`
5. Register resource handlers with `McpSchema.Resource`
6. Register prompt handlers with `McpSchema.Prompt`

See `MCP_IMPLEMENTATION.md` for detailed implementation guide and architecture documentation.
