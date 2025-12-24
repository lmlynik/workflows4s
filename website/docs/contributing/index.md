---
sidebar_position: 1
---

# Contributing to Workflows4s

Welcome to the Workflows4s contributor guide!

## Areas of Contribution

### Implementing New Effect Modules

Workflows4s is designed to support multiple effect systems through a pluggable `Effect[F[_]]` typeclass. See [Implementing Effect Modules](./effect-modules) for a step-by-step guide.

Currently supported effect systems:
- **cats.Id** - Synchronous, blocking execution (in core)
- **scala.concurrent.Future** - Asynchronous via Scala Futures (in core)
- **cats.effect.IO** - Cats Effect integration (`workflows4s-cats`)
- **zio.Task** - ZIO integration (`workflows4s-zio`)
- **workflows4s.ox.Direct** - Ox direct-style (`workflows4s-ox`)

### Adding Runtime Backends

Runtime backends persist workflow state and events. See existing implementations:
- `InMemoryRuntime` - In-memory, non-persistent
- `DatabaseRuntime` - Doobie/PostgreSQL persistence
- `PekkoRuntime` - Apache Pekko event sourcing

### Development Setup

```bash
# Clone the repository
git clone https://github.com/business4s/workflows4s.git
cd workflows4s

# Compile all modules
sbt compile

# Run tests
sbt test

# Format code (required before commits)
sbt scalafmtAll
```

### Pull Request Guidelines

1. Fork the repository and create a feature branch
2. Write tests for new functionality
3. Run `sbt prePR` to verify compilation, tests, and formatting
4. Submit a pull request with a clear description
