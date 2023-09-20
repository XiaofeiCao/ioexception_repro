# Repository for reproducing thread stuck

## [BatchCreateResourceGroupTests](https://github.com/XiaofeiCao/ioexception_repro/blob/main/src/test/java/com/azure/resourcemanager/repro/ioexception/test/undeliverable/BatchCreateResourceGroupTests.java)
For https://github.com/Azure/azure-sdk-for-java/issues/33056
### Step summarize(Parallelism of 100): 
1. Create resource group
2. Check if network security group exists
3. Deploy network security group if not exist
4. Delete network security group
5. Delete resource group

Replace Line108 
```java
.flatMap(resourceGroup -> azureResourceManager.deployments().manager().serviceClient().getDeployments().checkExistenceAsync(resourceGroupName, nsgName))
```
with Line107 
```java
.flatMap(resourceGroup -> Mono.fromCallable(() -> azureResourceManager.deployments().checkExistence(resourceGroupName, nsgName)))
```
and it'll occasionally get stuck at `checkExistence`.

### How to run the test
1. Setup environment variables for Azure [Authorization](https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/resourcemanager/README.md#authentication)
- `AZURE_CLIENT_ID` for Azure client ID.
- `AZURE_TENANT_ID` for Azure tenant ID.
- `AZURE_CLIENT_SECRET` or `AZURE_CLIENT_CERTIFICATE_PATH` for client secret or client certificate.

In addition, Azure subscription ID can be configured via environment variable `AZURE_SUBSCRIPTION_ID`.

2. Execute test method `createResourceGroups`
