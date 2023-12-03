# Naming and signing daemon (NSD) for k8s.home

Provides
- Name server for LoadBalancer resources
- Automatic certificate signer

## How to compile and run:
```
$ gradle installdist
...
$ ./build/install/nsd4k/bin/nsd4k <config>
$ # OR
$ ./build/install/nsd4k/bin/nsd4k -f <config-file>
```
This program can run in container, but please ensure that `openssl` is present 
in the container you build.

## Configuration:
Example:
```json
$ cat cfg.json
{
        "datadir": "/mnt/fast/nsd4k/data",
        "domains": ["home", "home.arhome.info"],
        "distinguishedNamePrefix": "/C=IN/O=home.arhome.info",
        "caCommonName": "ca.private.arhome.info",
        "dnsListen": [{"addr": "0.0.0.0", "port": 5353}],
        "privilegedNamespaces": ["dns"]
}
```
For configuration schema please refer to `ConfigDto.java`. 

## DNS entries and certificate generation for load balancers
Based on labels, services are selected and DNS entries are created for them:
```
$ kubectl get -n nextcloud service -o yaml
apiVersion: v1
items:
- apiVersion: v1
  kind: Service
  metadata:
    labels:
      nsd4k.k8s.home.arhome.info/name: default
      nsd4k.k8s.home.arhome.info/secret: nextcloud-tls
      ...
    name: nextcloud
    namespace: nextcloud
    ...
  spec:
    type: LoadBalancer
    allocateLoadBalancerNodePorts: false
    ...
```
IP addresses are automatically picked up from service status and made available
via DNS.
Generated certificates are added to secrets as below:
```
$ kubectl get -n nextcloud secret nextcloud-tls -o yaml
apiVersion: v1
data:
  cacert.pem: ...
  cert.pem: ...
  key.pem: ...
kind: Secret
metadata:
  name: nextcloud-tls
  namespace: nextcloud
  ...
type: Opaque
```

## Static DNS entries
```
$ kubectl get configmap main-dns -o yaml -n dns
apiVersion: v1
data:
  entries: |
    {
      "A": {
          "ns": ["172.16.0.4", "fc00::4"],
          "k8s": ["172.16.0.4", "fc00::4"]
        }
    }
kind: ConfigMap
metadata:
  annotations:
  ...
  labels:
    nsd4k.k8s.home.arhome.info/dns: dns
    nsd4k.k8s.home.arhome.info/suffix: ""
  name: main-dns
  namespace: dns
  ...
```
