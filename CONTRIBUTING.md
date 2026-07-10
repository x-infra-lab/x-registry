# Contributing to X-Registry

Thank you for your interest in contributing to X-Registry!

## Development Setup

1. Fork and clone the repository
2. Ensure JDK 17+ and Maven 3.8+ are installed
3. Build: `mvn clean install`
4. Run tests: `mvn test`

## Code Style

- Follow [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html)
- Use 4 spaces for indentation
- Maximum line length: 120 characters

## Branch Model

- `main` — stable release branch
- Feature branches: `feature/description`
- Bug fix branches: `fix/description`

## Making Changes

1. Create a feature branch from `main`
2. Write code and tests
3. Ensure all tests pass: `mvn verify`
4. Commit with clear messages
5. Open a Pull Request against `main`

## Commit Messages

Use clear, descriptive commit messages:

```
feat: add config gray release support
fix: resolve race condition in push aggregator
docs: update deployment guide
refactor: extract KVStore interface from InstanceStore
```

## Pull Requests

- Keep PRs focused on a single feature or fix
- Include tests for new functionality
- Update documentation if APIs change
- Ensure CI passes before requesting review

## Reporting Issues

- Use GitHub Issues for bug reports and feature requests
- Include steps to reproduce for bugs
- Include version and environment information

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
