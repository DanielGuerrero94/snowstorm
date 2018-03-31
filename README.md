# ❄️ Snowstorm Terminology Server

## (Not Production Ready)

Snowstorm is currently a proof of concept SNOMED CT terminology server built on top of Elasticsearch, with a focus on performance and enterprise scalability.


##Documentation
Documentation is sparse for now, but will be improved as the project moves out of a proof of concept phase.

### Run Snowstorm
Once Elasticsearch is running build and run Snowstorm:
```
mvn clean install
java -jar target/snowstorm*.jar
```

### Docker

It is strongly recommended to use docker compose, instead of the snowstorm container on its own.

The docker-compose.yml in the repo option will run everything necessary to use Snowstorm without the need to build anything. However, **you will need the previously generated SNOMED CT elasticsearch indices** which you can either generate yourself, see the [snomed loading instructions here](docs/loading-snomed.md), or contact [techsupport@snomed.org](mailto::techsupport@snomed.org) to get access to a copy of the already generated indices.

Once you have the indices, you can either unzip them into a local ~/elastic folder or change the following line in [docker-compose.yml](docker-compose.yml) from ~/elastic to a local folder of your choice:
```    
    volumes:
      - ~/elastic:/usr/share/elasticsearch/data
```
Once done, then simply run:
```
docker-compose up -d
```


##Documentation
Documentation is sparse for now, but will be improved as the project moves out of a proof of concept phase.

- [Getting Started](getting-started.md)
- [Loading SNOMED](loading-snomed.md)
- [Using the API](using-the-api.md)
- [Configuration Guide](configuration-guide.md)
