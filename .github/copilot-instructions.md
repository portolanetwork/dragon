# Dragon MCP Hub - GitHub Copilot Instructions

## Project Overview

Dragon is an open-source, cloud-native, distributed hub for Model Context Protocol (MCP). It's built on Apache Pekko's actor system and provides a foundation for scalable MCP routing and tool aggregation.

## Architecture

Dragon uses a multi-language, distributed architecture:
- **Backend (Scala)**: Located in `scala/turnstile/` - Core MCP gateway and server management
- **Frontend (TypeScript/React)**: Located in `js/console/` - Web-based console UI
- **Protocol Definitions**: Located in `resources/proto/` - gRPC/Protobuf definitions

### Key Components

- **MCP Gateway** (`mcp_gateway`): Handles MCP protocol routing and aggregation
- **MCP Server** (`mcp_server`): Manages downstream MCP server connections
- **MCP Client** (`mcp_client`): Client-side MCP interactions
- **MCP Tools** (`mcp_tools`): Tool implementations and management
- **Auth** (`auth`): OAuth2/OIDC authentication with JWT validation
- **Management API** (`mgmt`): gRPC-based server management
- **Database** (`db`): PostgreSQL persistence with Slick and Flyway migrations

## Technology Stack

### Backend (Scala)
- **Language**: Scala 3.7.3 (modern Scala with minimal boilerplate)
- **Build Tool**: sbt 1.11+
- **Actor System**: Apache Pekko 1.3.0 (Akka fork)
- **HTTP/gRPC**: Apache Pekko HTTP and gRPC
- **Database**: PostgreSQL with Slick 3.5.2 for database access
- **Migrations**: Flyway for database schema management
- **MCP SDK**: MCP Java SDK 0.16.0
- **Reactive HTTP**: Spring WebFlux (embedded)
- **Auth**: JWT/Auth0 for OAuth2/OIDC

### Frontend (TypeScript/React)
- **Framework**: React (latest) with TypeScript 5.9+
- **Build Tool**: Vite (latest)
- **UI Framework**: Material-UI (MUI) v7
- **State Management**: React hooks
- **Auth**: Auth0 React SDK
- **API Communication**: gRPC-Web with Protobuf
- **Charts**: MUI X-Charts
- **Data Grid**: MUI X-Data-Grid and AG Grid Enterprise

## Build and Test Instructions

### Scala Backend

#### Prerequisites
- JDK 17 or later
- sbt 1.11+
- PostgreSQL 12+

#### Build Commands
```bash
cd scala/turnstile

# Compile
sbt compile

# Package
sbt package

# Clean and build
make build-clean

# Run tests
sbt test

# Run the server
sbt "runMain app.dragon.turnstile.main.Turnstile"
```

#### Environment Variables Required
```bash
export DEPLOYMENT_NAME=default
export DATABASE_URL=<db-url>
export DATABASE_USER=<db-user>
export DATABASE_PASSWORD=<db-password>
export AUTH0_DOMAIN=<auth0-domain>
export AUTH0_AUDIENCE=<auth0-audience>
export AUTH0_CLIENT_AUDIENCE=<auth0-client-audience>
```

### Frontend (JavaScript/TypeScript)

#### Build Commands
```bash
cd js/console

# Install dependencies
npm install

# Development server
npm run dev

# Build for production
npm run build

# Preview production build
npm run preview
```

#### Generate Protobuf stubs
```bash
cd js/console
make protoc-js
```

## Coding Standards and Conventions

### Scala
- Use Scala 3 syntax (no Scala 2 compatibility)
- Follow functional programming principles
- Use for-comprehensions for async operations
- Leverage Scala 3's strong type system
- Minimize boilerplate using given/using clauses
- Actor-based patterns with Apache Pekko
- Use Slick for database queries
- Keep business logic separate from infrastructure concerns

### TypeScript/React
- Use TypeScript strict mode
- Functional components with hooks (no class components)
- Material-UI components for consistency
- Follow React best practices for state management
- Use proper typing for all props and state
- Organize components by feature/domain

### General
- Keep code DRY (Don't Repeat Yourself)
- Write self-documenting code with clear variable/function names
- Add comments only when necessary to explain "why", not "what"
- Follow the existing code style in each file
- Ensure proper error handling and logging

## Project Structure

```
dragon/
├── .github/                     # GitHub configuration
├── scala/turnstile/             # Scala backend
│   ├── src/
│   │   ├── main/
│   │   │   ├── scala/           # Scala source code
│   │   │   │   └── app/dragon/turnstile/
│   │   │   │       ├── auth/           # Authentication logic
│   │   │   │       ├── config/         # Configuration
│   │   │   │       ├── db/             # Database access
│   │   │   │       ├── mcp_client/     # MCP client
│   │   │   │       ├── mcp_gateway/    # MCP gateway
│   │   │   │       ├── mcp_server/     # MCP server management
│   │   │   │       ├── mcp_tools/      # Tool implementations
│   │   │   │       ├── mgmt/           # Management API
│   │   │   │       ├── main/           # Application entry points
│   │   │   │       └── utils/          # Utilities
│   │   │   └── resources/
│   │   │       ├── application.conf    # Application configuration
│   │   │       └── db/migration/       # Flyway migrations
│   │   └── test/scala/          # Test code
│   ├── build.sbt                # SBT build definition
│   └── Makefile                 # Build automation
├── js/console/                  # TypeScript/React frontend
│   ├── src/
│   │   ├── components/          # React components
│   │   ├── proto/               # Generated protobuf code
│   │   └── ...
│   ├── package.json             # NPM dependencies
│   ├── tsconfig.json            # TypeScript configuration
│   ├── vite.config.ts           # Vite build configuration
│   └── Makefile                 # Build automation
└── resources/proto/             # Shared protobuf definitions
```

## Authentication and Security

- **OAuth2/OIDC**: Using Auth0 for authentication
- **JWT Validation**: All API requests require valid JWT tokens
- **User Isolation**: Tenant/user-level isolation for MCP server instances
- **Authorization**: Bearer token authentication for gRPC and HTTP APIs
- **Token Refresh**: Automatic token refresh support

### Security Best Practices
- Never commit secrets, credentials, or tokens to source code
- Use environment variables for sensitive configuration
- Validate all inputs
- Follow principle of least privilege
- Keep dependencies up to date

## Database

- **Database**: PostgreSQL 12+
- **Migrations**: Located in `scala/turnstile/src/main/resources/db/migration/`
- **Access Layer**: Slick for type-safe database queries
- **Migration Tool**: Flyway (runs automatically on startup)

## Testing

- **Scala Tests**: Located in `scala/turnstile/src/test/scala/`
- **Test Framework**: ScalaTest (based on existing test files)
- **Run Tests**: `sbt test` from `scala/turnstile/` directory

When adding new features:
1. Add corresponding tests in the appropriate test directory
2. Follow existing test patterns and naming conventions
3. Ensure tests are independent and can run in any order
4. Mock external dependencies

## API Endpoints

- **gRPC API**: `localhost:8080` (server management)
- **gRPC-Web API**: `localhost:8081` (server management from UI)
- **MCP Gateway**: `localhost:8082/mcp` (MCP protocol endpoint)

## Development Workflow

1. **Make Changes**: Edit code in appropriate directory
2. **Build**: Use `make build` or direct sbt/npm commands
3. **Test**: Run tests with `sbt test` or `npm test`
4. **Verify**: Test changes locally with running instances
5. **Commit**: Use semantic commit messages

## Contributing Guidelines

At this early stage, external code contributions are not yet accepted. Please open issues or start discussions for:
- Bug reports
- Feature requests
- Questions or feedback

All code is released under the Apache License 2.0.

## Important Notes

- **Branding**: The name "Dragon" and related branding are protected trademarks
- **License**: Apache License 2.0
- **Distributed Architecture**: Code should be designed for horizontal scalability
- **Reactive Principles**: Follow the Reactive Manifesto principles
- **Asynchronous**: Prefer asynchronous, non-blocking operations
- **Type Safety**: Leverage strong typing to catch errors at compile time

## Resources

- [Configuration Reference](scala/turnstile/src/main/resources/application.conf)
- [Database Schema](scala/turnstile/src/main/resources/db/migration/)
- [API Documentation](resources/proto/)
- [Auth0 Setup Guide](scala/turnstile/docs/auth-with-auth0.md)
