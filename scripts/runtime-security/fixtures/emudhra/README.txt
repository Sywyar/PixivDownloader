Certificate chains served by the eMudhra test hosts
====================================================

Each file holds the full chain sent by one of the test hosts at
https://emudhra.we-have-a-problem.com/, in PEM format, leaf first.
The chains were captured on 20 October 2026.

  <kind>-<gen>-emudhra.we-have-a-problem.com.pem
      leaf, impacted intermediate, emSign Root TLS CA - G3 (cross-signed
      by emSign ECC Root CA - G3)

  <kind>-<gen>-aaa-emudhra.we-have-a-problem.com.pem
      leaf, impacted intermediate cross-signed by AAA Certificate Services

where <kind> is dv, ov or ev and <gen> is g3b or g3c.

Offline check
-------------

To see whether a trust store still accepts a chain, verify the leaf
against that store using the rest of the file as untrusted intermediates.
For the OpenSSL default store:

  openssl verify -show_chain -untrusted FILE.pem FILE.pem

For a specific bundle:

  openssl verify -show_chain -CAfile /path/to/bundle.pem -untrusted FILE.pem FILE.pem

"OK" means the store still trusts the compromised intermediate.
An error naming the issuer certificate means trust has been removed.
Note that openssl verify does not check revocation unless asked to.
