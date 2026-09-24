# ms.http.request.method

**Tags**: 
[http](categories.md#http-properties)

**Type**: string

**Format**: one of the values of get, put, post, delete, and post_batch

**Default value**: blank (equivalent to GET)

## Related 

## Description 

The expected HTTP method to send the requests, decided by the API.

- GET: all parameters are specified in the URL as URL parameters
- POST: parameters are sent to API in request body
- PUT: parameters are sent to API in request body
- DELETE: parameters are sent to API in request body
- POST_BATCH: many sub-requests are combined into a single `multipart/mixed` request (RFC 2046,
  where each part is an `application/http` message). This is the batching alternative to POST:
  rather than one JSON body, each element of the payload `data` array becomes its own embedded HTTP
  sub-request. All sub-requests share the same method, relative URL, and content type. Use it
  against a REST API that exposes a single `/batch` endpoint.

**Note**: URL parameters are URL encoded.

### POST_BATCH payload

POST_BATCH reads its content from a payload (an `ms.secondary.input` of category `payload`) with
this shape:

```json
{
  "batchRelativeUrl": "/v1/entries",
  "batchMethod": "POST",
  "batchContentType": "application/json",
  "data": [
    {"id": "a", "action": "UPDATE"},
    {"id": "b", "action": "UPDATE"}
  ]
}
```

- `batchRelativeUrl` (required): relative URL applied to every sub-request.
- `batchMethod` (optional, default `POST`): HTTP method of every sub-request.
- `batchContentType` (optional, default `application/json`): content type of every sub-request body.
- `data` (required): a non-empty array; one embedded sub-request is emitted per element.

`ms.source.uri` should point at the batch endpoint (for example `https://api.example.com/batch`).
Do **not** set a `Content-Type` request header for POST_BATCH — the entity emits
`multipart/mixed; boundary=...` with a generated boundary, and a manual header would drop the
boundary. Non-payload parameters are used only for URI template substitution, not for the request
body.

[back to summary](summary.md#mshttprequestmethod)

