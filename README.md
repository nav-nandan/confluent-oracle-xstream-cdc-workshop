# psa-oracle-xstream-cdc-workshop
Docker rig to test Oracle XStream CDC Source Connector to Oracle 19c

## Prepare Working Directory

```
git clone https://github.com/nav-nandan/psa-oracle-xstream-cdc-workshop.git

cd psa-oracle-xstream-cdc-workshop
```

Create an account on Oracle Container Registry (OCR) - https://container-registry.oracle.com/ and accept Oracle Standard Terms and Restrictions for `enterprise` repository to use Oracle Database Enterprise Edition images.

Get Docker to use OCR (via Auth Token) to authorize image pull
```
docker login container-registry.oracle.com
```

Run docker compose and check all services are up and running
```
docker-compose up -d
docker ps
```

Exec into oracle19c container and prepare DB for XStream capture and CDC connector pipeline
```
sqlplus / as sysdba

ALTER SYSTEM SET enable_goldengate_replication=TRUE SCOPE=BOTH;

SELECT VALUE FROM V$PARAMETER WHERE NAME = 'enable_goldengate_replication';

SELECT LOG_MODE FROM V$DATABASE;

SHUTDOWN IMMEDIATE;
STARTUP MOUNT;
ALTER DATABASE ARCHIVELOG;
ALTER DATABASE OPEN;

ALTER SESSION SET CONTAINER = CDB$ROOT;
ALTER DATABASE ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS;

SELECT SUPPLEMENTAL_LOG_DATA_MIN, SUPPLEMENTAL_LOG_DATA_ALL FROM V$DATABASE;

CREATE USER c##cfltadmin IDENTIFIED BY password
  DEFAULT TABLESPACE USERS
  QUOTA UNLIMITED ON USERS
  CONTAINER=ALL;

GRANT CREATE SESSION, SET CONTAINER TO c##cfltadmin CONTAINER=ALL;

BEGIN
  DBMS_XSTREAM_AUTH.GRANT_ADMIN_PRIVILEGE(
    grantee                 => 'c##cfltadmin',
    privilege_type          => 'CAPTURE',
    grant_select_privileges => TRUE,
    container               => 'ALL');
END;

CREATE USER c##cfltuser IDENTIFIED BY password
  DEFAULT TABLESPACE USERS
  QUOTA UNLIMITED ON USERS
  CONTAINER=ALL;

GRANT CREATE SESSION, SET CONTAINER TO c##cfltuser CONTAINER=ALL;

GRANT SELECT_CATALOG_ROLE TO c##cfltuser CONTAINER=ALL;

GRANT SELECT ANY TABLE TO c##cfltuser CONTAINER=ALL;
GRANT LOCK ANY TABLE TO c##cfltuser CONTAINER=ALL;
GRANT FLASHBACK ANY TABLE TO c##cfltuser CONTAINER=ALL;

GRANT CREATE SESSION, 
      CREATE TABLE, 
      CREATE VIEW, 
      CREATE SEQUENCE, 
      CREATE PROCEDURE 
TO c##cfltuser CONTAINER=ALL;

## create TEST table with some sample data by logging in as c##cfltuser
sqlplus c##cfltuser/password@//localhost:1521/ORCLCDB

sqlplus c##cfltadmin/password@//localhost:1521/ORCLCDB

DECLARE
  tables  DBMS_UTILITY.UNCL_ARRAY;
  schemas DBMS_UTILITY.UNCL_ARRAY;
BEGIN
  tables(1)  := 'c##cfltuser.test';
  schemas(1) := NULL;
  DBMS_XSTREAM_ADM.CREATE_OUTBOUND(
     server_name           =>  'orclxout',
     source_container_name =>  'CDB$ROOT',
     capture_name          =>  'orclxcap',
     table_names           =>  tables,
     schema_names          =>  schemas);
END;

SELECT capture_name, status, purpose 
FROM dba_capture;

BEGIN
  DBMS_XSTREAM_ADM.ALTER_OUTBOUND(
     server_name  => 'orclxout',
     connect_user => 'c##cfltuser');
END;

BEGIN
  DBMS_XSTREAM_ADM.SET_PARAMETER(
    streams_type => 'capture',
    streams_name => 'orclxcap',
    parameter    => 'max_sga_size',
    value        => '1024');
END;

BEGIN
  DBMS_XSTREAM_ADM.SET_PARAMETER(
    streams_type => 'apply',
    streams_name => 'orclxout',
    parameter    => 'max_sga_size',
    value        => '1024');
END;

BEGIN
  DBMS_CAPTURE_ADM.ALTER_CAPTURE(
    capture_name              => 'orclxcap',
    checkpoint_retention_time => 7);
END;
```

Deploy Oracle XStream CDC Source Connector via the Kafka Connect REST endpoint
```
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d '{
  "name": "oracle-xstream-cdb-connector",
  "config": {
    "connector.class": "io.confluent.connect.oracle.xstream.cdc.OracleXStreamSourceConnector",
    "confluent.topic.bootstrap.servers": "confluent-server:19092",
    "tasks.max": "1",
    "database.hostname": "oracle19c",
    "database.port": "1521",
    "database.user": "C##CFLTUSER",
    "database.password": "password",
    "database.dbname": "ORCLCDB",
    "database.service.name": "ORCLCDB",
    "database.out.server.name": "ORCLXOUT",
    "topic.prefix": "cflt",
    "table.include.list": "C##CFLTUSER.TEST",
    "key.converter": "io.confluent.connect.avro.AvroConverter",
    "value.converter": "io.confluent.connect.avro.AvroConverter",
    "key.converter.schema.registry.url": "http://schema-registry:8081",
    "value.converter.schema.registry.url": "http://schema-registry:8081",
    "schema.history.internal.kafka.topic": "__orcl-schema-changes.cflt",
    "schema.history.internal.kafka.bootstrap.servers": "confluent-server:19092"
  }
}' | jq .
```

Verify schema history and changelog topics for initial snapshot and incremental CDC
```
kcat -b confluent-server:19092 -t __orcl-schema-changes.cflt -q
kcat -b confluent-server:19092 -t cflt.C__CFLTUSER.TEST -q
kcat -b confluent-server:9092 \
  -t cflt.C__CFLTUSER.TEST \
  -C \
  -f '\n--- [Key: %k | Offset: %o | Partition: %p] ---\nPayload: %s\n'

kcat -b confluent-server:19092 \
  -t cflt.C__CFLTUSER.TEST \
  -r http://schema-registry:8081 \
  -s value=avro \
  -C -o beginning -e | jq .
```
