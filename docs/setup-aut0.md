- Create Applications -> API -> Create API. Name: Dragon API, Audience: https://dragonpi.app/api
- Create Applicaitons -> Applications -> Single Page App. Note client ID, Domain.
  + Update callback url: Your application console base url
  - Update Allowed Web Origins: Your application console base url

- Update turnstile conf file with server clientId, domain
- Update react deployment.jsx:
  - ClientId
  - Domain
  - Audience: <from-above>

