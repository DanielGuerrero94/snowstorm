# Getting Started

## Prerequisites

- Java 8
- Maven 3
- [SNOMED CT International Edition RF2 release files](https://www.snomed.org/snomed-ct/get-snomed-ct)
- About 8G of memory

## More on Memory Requirements

As a minimum Snowstorm should have 2G and Elasticsearch should have 4G of memory in order to import a Snapshot and perform ECL queries. 
Elasticsearch will work best with another 4G of memory left free on the server for OS level disk caching. 

## Setup
### Install Elasticsearch
Download and unzip [Elasticsearch **6.0.1**](https://www.elastic.co/downloads/past-releases/elasticsearch-6-0-1) (must be this version).

Update the configuration file _config/jvm.options_ with the memory options `-Xms4g` and `-Xmx4g`.

### Build Snowstorm
Build Snowstorm using maven:
```bash
mvn clean package
```

## Start Snowstorm

First start Elasticsearch from wherever it has been installed.
```bash
./bin/elasticsearch
```

On the first run of Snowstorm the SNOMED CT data needs to be loaded. [Follow instructions here](loading-snomed.md).

On subsequent runs just start Snowstorm.
```bash
java -Xms2g -Xmx2g -jar target/snowstorm*.jar
```
