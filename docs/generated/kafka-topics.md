# sim-service events 1.0.0 documentation

Messages sim-service consumes and publishes. Change this file before the Java code.

## Table of Contents

* [Operations](#operations)
  * [RECEIVE sim.ordered](#receive-simordered-operation)
  * [SEND sim.ordered.dlt](#send-simordereddlt-operation)
  * [SEND sim.activated](#send-simactivated-operation)
  * [SEND sim.deactivated](#send-simdeactivated-operation)

## Operations

### RECEIVE `sim.ordered` Operation

* Operation ID: `receiveSimOrdered`

#### Message `SimOrdered`

##### Payload

| Name | Type | Description | Value | Constraints | Notes |
|---|---|---|---|---|---|
| (root) | object | - | - | - | **additional properties are allowed** |
| iccid | string | - | - | pattern (`^[0-9]{19,20}$`) | **required** |
| msisdn | string | - | - | pattern (`^[1-9][0-9]{0,14}$`) | **required** |
| orderId | string | - | - | - | **required** |

> Examples of payload _(generated)_

```json
{
  "iccid": "string",
  "msisdn": "string",
  "orderId": "string"
}
```



### SEND `sim.ordered.dlt` Operation

* Operation ID: `publishSimOrderedDeadLetter`

#### Message `SimOrderedDeadLetter`

* Message ID: `SimOrderedDeadLetter`
* Content type: [application/octet-stream](https://www.iana.org/assignments/media-types/application/octet-stream)

Original sim.ordered value bytes and key, unchanged (including a null value). Spring Kafka's dead-letter publisher writes it and may add its own kafka_dlt-* headers. Delivery is at least once; eventId is new for every dead-letter publish, so a record published again after a consumer restart has a different eventId.


##### Headers

| Name | Type | Description | Value | Constraints | Notes |
|---|---|---|---|---|---|
| (root) | object | - | - | - | **additional properties are allowed** |
| reason | string | UTF-8 explanation of the rejection. | - | - | **required** |
| eventId | string | UTF-8 identifier of this dead-letter publish. | - | format (`uuid`) | **required** |

> Examples of headers _(generated)_

```json
{
  "reason": "string",
  "eventId": "d6703cc8-9e79-415d-ac03-a4dc7f6ab43c"
}
```


##### Payload

| Name | Type | Description | Value | Constraints | Notes |
|---|---|---|---|---|---|
| (root) | any | Original Kafka value bytes, which may not be valid JSON or UTF-8, or may be null. | - | - | **additional properties are allowed** |

> Examples of payload _(generated)_

```json
""
```



### SEND `sim.activated` Operation

* Operation ID: `publishSimActivated`

#### Message `SimActivated`

##### Payload

| Name | Type | Description | Value | Constraints | Notes |
|---|---|---|---|---|---|
| (root) | object | - | - | - | **additional properties are NOT allowed** |
| eventId | string | Same value on a redelivery; consumers drop duplicates by it. | - | format (`uuid`) | **required** |
| iccid | string | - | - | - | **required** |
| msisdn | string | - | - | - | **required** |
| activatedAt | string | - | - | format (`date-time`) | **required** |

> Examples of payload _(generated)_

```json
{
  "eventId": "d6703cc8-9e79-415d-ac03-a4dc7f6ab43c",
  "iccid": "string",
  "msisdn": "string",
  "activatedAt": "2019-08-24T14:15:22Z"
}
```



### SEND `sim.deactivated` Operation

* Operation ID: `publishSimDeactivated`

#### Message `SimDeactivated`

##### Payload

| Name | Type | Description | Value | Constraints | Notes |
|---|---|---|---|---|---|
| (root) | object | - | - | - | **additional properties are NOT allowed** |
| eventId | string | Same value on a redelivery; consumers drop duplicates by it. | - | format (`uuid`) | **required** |
| iccid | string | - | - | - | **required** |
| msisdn | string | - | - | - | **required** |
| deactivatedAt | string | - | - | format (`date-time`) | **required** |

> Examples of payload _(generated)_

```json
{
  "eventId": "d6703cc8-9e79-415d-ac03-a4dc7f6ab43c",
  "iccid": "string",
  "msisdn": "string",
  "deactivatedAt": "2019-08-24T14:15:22Z"
}
```



