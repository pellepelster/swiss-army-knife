# Glossary

The canonical vocabulary of this repository. Use these terms in code, documentation and the
user interface. Use the same term for the same thing every time.

- **sak name**: The unique name of a swiss army knife deployment. Each deployment has one sak
  name. Two deployments cannot have the same sak name. Each cloud resource of a deployment has
  the label `sak.pelle.io/name=<sak name>` (refer to "Resource labeling" in
  [AGENT.md](AGENT.md)).
- **root domain** (`root_domain`): The domain under which a deployment serves its apps. The
  production deployment uses `pelle.io`. A local deployment uses `localhost`.
