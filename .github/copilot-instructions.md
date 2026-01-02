# GitHub Copilot Instructions

This repository implements the Cashu protocol. When using GitHub Copilot, keep the following guidelines in mind:

- Use Conventional Commits for titles and commit messages (e.g., `feat(scope): message`).
- Ensure pull requests include a clear description and test results.
- Reference related issues using `Closes #123` when applicable.
- When implementing features, consult the NUT specifications:
  - [NUT-00](https://github.com/cashubtc/nuts/blob/main/00.md)
  - [NUT-01](https://github.com/cashubtc/nuts/blob/main/01.md)
  - [NUT-02](https://github.com/cashubtc/nuts/blob/main/02.md)
  - [NUT-03](https://github.com/cashubtc/nuts/blob/main/03.md)
  - [NUT-04](https://github.com/cashubtc/nuts/blob/main/04.md)
  - [NUT-05](https://github.com/cashubtc/nuts/blob/main/05.md)
  - [NUT-06](https://github.com/cashubtc/nuts/blob/main/06.md)
  - [NUT-07](https://github.com/cashubtc/nuts/blob/main/07.md)
  - [NUT-08](https://github.com/cashubtc/nuts/blob/main/08.md)
  - [NUT-09](https://github.com/cashubtc/nuts/blob/main/09.md)
  - [NUT-10](https://github.com/cashubtc/nuts/blob/main/10.md)
  - [NUT-11](https://github.com/cashubtc/nuts/blob/main/11.md)
  - [NUT-12](https://github.com/cashubtc/nuts/blob/main/12.md)
  - [NUT-13](https://github.com/cashubtc/nuts/blob/main/13.md)
  - [NUT-14](https://github.com/cashubtc/nuts/blob/main/14.md)
  - [NUT-15](https://github.com/cashubtc/nuts/blob/main/15.md)
  - [NUT-16](https://github.com/cashubtc/nuts/blob/main/16.md)
  - [NUT-17](https://github.com/cashubtc/nuts/blob/main/17.md)
  - [NUT-18](https://github.com/cashubtc/nuts/blob/main/18.md)
  - [NUT-19](https://github.com/cashubtc/nuts/blob/main/19.md)
  - [NUT-20](https://github.com/cashubtc/nuts/blob/main/20.md)
  - [NUT-21](https://github.com/cashubtc/nuts/blob/main/21.md)
  - [NUT-22](https://github.com/cashubtc/nuts/blob/main/22.md)
  - [NUT-23](https://github.com/cashubtc/nuts/blob/main/23.md)
  - [NUT-24](https://github.com/cashubtc/nuts/blob/main/24.md)
- Run `mvn -q verify` before committing code.
- Document new features in the README or related docs.
- Maintain Java 21 compatibility and update `pom.xml` for new dependencies.
- Remove unused imports.

These instructions help Copilot produce code that respects the repository's conventions and protocol requirements.