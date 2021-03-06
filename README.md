# ❄️ Snowstorm Terminology Server

Snowstorm is a prototype SNOMED CT terminology server built on top of Elasticsearch, with a focus on performance and enterprise scalability.

## Project Status

**Read-Only** - tested and already used in the SNOMED International Browser for ECL queries. Snowstorm can be used in local implementations to query SNOMED CT with the following features:
- Hosting multiple extensions alongside the International Edition of SNOMED CT
- Multi-lingual search and content retrieval
- Fully ECL v1.3 compliant
- Full history (depending on initial RF2 import decision)

**Authoring** - although write and update features are available this aspect is currently not production ready, having not been fully tested, so should be considered proof of concept.

Please add any issues or any questions in the [GitHub issues page](https://github.com/IHTSDO/snowstorm/issues).

## Documentation
Documentation is sparse for now, but will be improved as the project moves out of a proof of concept phase.

- [Getting Started (plain installation)](docs/getting-started.md)
- [Loading SNOMED](docs/loading-snomed.md)
- [Using the API](docs/using-the-api.md)
- [Configuration Guide](docs/configuration-guide.md)
- [Docker Quickstart (Experimental)](docs/using-docker.md)

## Contributing

1. Fork it!
2. Create your feature branch: `git checkout -b my-new-feature`
3. Commit your changes: `git commit -am 'Add some feature'`
4. Push to the branch: `git push origin my-new-feature`
5. Submit a pull request

## License

Apache 2.0 

See the included LICENSE file for details.
