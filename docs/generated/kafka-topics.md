# sim-service events 1.0.0 documentation

Messages sim-service consumes and publishes. Change this file before the Java code.

## Table of Contents

* [Operations](#operations)
  * [RECEIVE sim.ordered](#receive-simordered-operation)
  * [SEND sim.ordered.dead-letter](#send-simordereddead-letter-operation)
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
| msisdn | string | - | - | pattern (`^[0-9]{7,15}$`) | **required** |
| orderId | string | - | - | - | **required** |

> Examples of payload _(generated)_

```json
{
  "iccid": "string",
  "msisdn": "string",
  "orderId": "string"
}
```



### SEND `sim.ordered.dead-letter` Operation

* Operation ID: `publishSimOrderedRejected`

#### Message `SimOrderedRejected`

A sim.ordered message that was not stored because it is not valid JSON or its ICCID or MSISDN is missing or malformed. Operations fix it and replay it.

##### Payload

| Name | Type | Description | Value | Constraints | Notes |
|---|---|---|---|---|---|
| (root) | object | - | - | - | **additional properties are NOT allowed** |
| eventId | string | Same value on a redelivery; consumers drop duplicates by it. | - | format (`uuid`) | **required** |
| reason | string | Why the message was rejected. | - | - | **required** |
| payload | string \| null | The original message exactly as received. | - | - | **required** |

> Examples of payload _(generated)_

```json
{
  "eventId": "d6703cc8-9e79-415d-ac03-a4dc7f6ab43c",
  "reason": "string",
  "payload": "string"
}
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



