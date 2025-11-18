import SpyderServiceInterface from "./SpyderServiceInterface";
import {
    AccessEnum,
    AuthEnum, ChatLocator,
    Connector,
    ConnectorExportLocator,
    EndpointFilepath,
    EndpointLocator,
    UserLocator
} from "../proto/portola/common/v1/objects";
import {User} from 'firebase/auth';
import {SpyderServiceClient} from "../proto/portola/spyder/v1/spyder_service.client";
import {
    CreateStripeCheckoutSessionRequest,
    AuthDataRequest,
    CaffeinateEndpointRequest,
    Client,
    ConnectorAccessControl,
    CopyPathRequest,
    CreateClientRequest,
    CreateConnectorExportRequest,
    CreatePathRequest,
    CreateTunnelFromExportRequest,
    CreateTunnelRequest,
    FindTunnelsRequest,
    GetAllConnectorExportsFromMeRequest,
    ReadDirRequest,
    RenamePathRequest,
    TrashPathRequest,
    GetAllChatRequest, CreateChatRequet,
} from "../proto/portola/spyder/v1/spyder_service";
import {TunnelRow} from "../dashboard/components/types/TunnelRow";

import {ExportedAssetRow} from "../dashboard/components/types/ExportedAsssetRow";
import {EndpointRow} from "../dashboard/components/types/EndpointRow";

import {EndpointFilepathRow} from "../dashboard/components/types/EndpointFilepathRow";
import {TreeNode} from "../dashboard/components/types/TreeNode";
import {RedemptionCode} from "../dashboard/components/types/RedemptionCode";
import {Chat} from "../dashboard/components/types/Chat";
import {AuthClientRow} from "../dashboard/components/types/AuthClientRow";


class SpyderProxy {
    private static instance: SpyderProxy;

    private client: SpyderServiceClient = SpyderServiceInterface.getInstance();

    // Private constructor to prevent direct instantiation
    private constructor() {
        // Initialization code here
    }

    // Static method to get the single instance of the class
    public static getInstance(): SpyderProxy {
        if (!SpyderProxy.instance) {
            SpyderProxy.instance = new SpyderProxy();
        }
        return SpyderProxy.instance;
    }

    private async getAccessToken(auth: User): Promise<string> {
        if (auth && typeof auth.getIdToken === 'function') {
            return new Promise((resolve, reject) => {
                auth.getIdToken(false)
                    .then((idToken) => {
                        resolve(idToken);
                    })
                    .catch((error) => {
                        reject(error);
                    });
            });
        } else {
            throw new Error("Invalid auth object. Expected a User instance.");
        }
    }

    public async putAuthData(user: User, codeChallenge: string): Promise<RedemptionCode> {
        let accessToken = await this.getAccessToken(user).then((accessToken) => {
            return accessToken;
        }).catch((error) => {
            console.error("Error getting access token: ", error.message);
            return "";
        });

        let metadata = {
            Authorization: `Bearer ${accessToken}`
        }

        const authDataRequest: AuthDataRequest = AuthDataRequest.create({
            accessToken: accessToken,
            refreshToken: user.refreshToken,
            codeChallenge: codeChallenge,
        });

        console.log("Putting auth data with request: ", authDataRequest);

        return await this.client.putAuthData(authDataRequest, {meta: metadata})
            .then(async (unaryCall) => {
                const code = unaryCall.response.code;

                console.log("Auth data put successfully");

                return {
                    code: code,
                };
            })
            .catch((error) => {
                console.error("Error putting auth data: ", error.message);
                return {
                    code: "",
                }
            });
    }

    public async createClient(user: User, endpointId: string): Promise<AuthClientRow> {
        let accessToken = await this.getAccessToken(user).then((accessToken) => {
            return accessToken;
        }).catch((error) => {
            console.error("Error getting access token: ", error.message);
            return "";
        });

        let metadata = {
            Authorization: `Bearer ${accessToken}`
        }

        const createClientRequest = CreateClientRequest.create({
            endpointId: endpointId,
        });

        console.log("Creating client with request: ", createClientRequest);

        return await this.client
            .createClient(createClientRequest, {meta: metadata})
            .then((unaryCall) => {
                console.log("Client created successfully: ", unaryCall.response);

                return {
                    id: unaryCall.response.clientId,
                    clientId: unaryCall.response.clientId,
                    clientSecret: unaryCall.response.clientSecret || '',
                    endpointId: unaryCall.response.endpointId || '',
                    clientTenantId: unaryCall.response.clientTenantId || '',
                };
            }).catch((error) => {
                console.error("Error creating client: ", error.message);
                return Promise.reject(error);
            });
    }

    public async deleteClient(user: User, clientId: string): Promise<void> {
        let accessToken = await this.getAccessToken(user).then((accessToken) => {
            return accessToken;
        }).catch((error) => {
            console.error("Error getting access token: ", error.message);
            return "";
        });

        let metadata = {
            Authorization: `Bearer ${accessToken}`
        }

        const client = Client.create({
            clientTenantId: user?.uid,
            clientId: clientId
        });

        //console.log("Deleting client with request: ", deleteClientRequest);

        return await this.client
            .deleteClient(client, {meta: metadata})
            .then(() => {
                console.log("Client deleted successfully");
            })
            .catch((error) => {
                console.error("Error deleting client: ", error.message);
            });
    }

    public async getAllClients(user: User): Promise<AuthClientRow[]> {
        let accessToken = await this.getAccessToken(user).then((accessToken) => {
            return accessToken;
        }).catch((error) => {
            console.error("Error getting access token: ", error.message);
            return "";
        });

        let metadata = {
            Authorization: `Bearer ${accessToken}`
        }

        return await this.client.getAllClients(UserLocator.create(), {meta: metadata})
            .then((unaryCall) => {
                const clientList = unaryCall.response.clientList;
                console.log("Client list: ", clientList);
                //return clientList.map(client => client.clientId);
                return clientList.map((client) => ({
                    id: client.clientId,
                    clientId: client.clientId,
                    clientSecret: client.clientSecret || '',
                    endpointId: client.endpointId || '',
                    clientTenantId: client.clientTenantId || '', // <-- Add this line
                }));
            })
            .catch((error) => {
                console.error("Error getting all clients: ", error.message);
                return [];
            });
    }

    public async getAllEndpoints(user: User): Promise<EndpointRow[]> {
        let metadata = await this.getAccessToken(user).then((accessToken) => {
            return {
                Authorization: `Bearer ${accessToken}`
            };
        }).catch((error) => {
            console.error("Error getting access token: ", error.message);
            return {};
        });

        const userLocator: UserLocator = UserLocator.create({
            userId: user?.uid,
        });

        console.log("userLocator: ", userLocator);

        return await this.client.getAllEndpoints(userLocator, {meta: metadata})
            .then(async (unaryCall) => {
                const endpointList = unaryCall.response.endpointList;

                console.log("-------------- Endpoint List: ", endpointList.length);

                const endpoints = await Promise.all(endpointList.map((endpoint) => {
                    if (endpoint.endpointLocator) {
                        console.log(`Processing endpoint: ${endpoint.username}`);
                        
                        return this.client.getEndpointStatus(endpoint.endpointLocator, {meta: metadata})
                            .then((unaryCall) => {
                                const currentEpochSec = Math.floor(Date.now() / 1000);
                                const lastOnlineSec = Number(unaryCall.response.lastOnlineSec);
                                const timeSinceLastOnline = lastOnlineSec == 0 ? 0 : currentEpochSec - lastOnlineSec;
                                const isOnline = unaryCall.response.statusEnum == 1;

                                const lastOnlineValue = timeSinceLastOnline == 0 ? "" : formatTimeSinceLastOnline(timeSinceLastOnline);

                                console.log(`Status for endpoint ${endpoint.endpointLocator?.endpointId}: ${unaryCall.response.statusEnum}`);
                                return {
                                    id: endpoint.endpointLocator?.endpointId,
                                    hostname: endpoint.hostname,
                                    username: endpoint.username,
                                    homedir: endpoint.homedir,
                                    agent_version: endpoint.agentVersion,
                                    isOnline: isOnline,// ? "Online" : "Offline",
                                    lastOnline: lastOnlineValue,
                                    isCaffeinated: unaryCall.response.isCaffeinated,
                                };
                            })
                            .catch((error) => {
                                console.log(`Error fetching status for endpoint ${endpoint.endpointLocator?.endpointId}: ${error.message}`);
                                return undefined;
                            });
                    } else {
                        console.log(`Endpoint locator is undefined for endpoint: ${endpoint.hostname}`);
                        return undefined;
                    }
                }));

                return endpoints.filter((endpoint): endpoint is EndpointRow => endpoint !== undefined);
            })
            .catch((error) => {
                console.log("Error: ", error.message)
                return [];
            });
    }

    public async getExportsForEndpoint(user: User, endpointId: string): Promise<ExportedAssetRow[]> {
        console.log('Fetching exports for endpoint...' + endpointId);

        let metadata = await this.getAccessToken(user).then((accessToken) => {
            return {
                Authorization: `Bearer ${accessToken}`
            };
        }).catch((error) => {
            console.error("Error getting access token: ", error.message);
            return {};
        });

        const getRequest: GetAllConnectorExportsFromMeRequest = GetAllConnectorExportsFromMeRequest.create({
            userId: user?.uid,
            endpointId: endpointId,
        });

        return await this.client
            .getAllConnectorExportsFromMe(getRequest, {meta: metadata})
            .then((unaryCall) => {
                const exportedAssets = unaryCall.response.connectorExportList;
                console.log("Exports for endpoint: ", exportedAssets);

                return exportedAssets.map((asset) => ({
                    id: asset.connectorExportLocator?.connectorExportId || '',
                    exportedAsset: getConnectorTypeDisplayStr(asset.connector),
                    toUser: asset.connectorAccessControl?.email || '',
                    fromUserId: '', // Ensure fromUserId is a string
                    fromUserName: '', // Ensure fromUserDisplayName is a string
                    fromUserEmail: '', // Ensure fromUser is a string
                    fromUserEmailVerified: false, // Ensure fromUserEmailVerified is a boolean
                    fromUserPicture: '', // Ensure fromUserPhotoURL is a string

                }));
            })
            .catch((error) => {
                console.log(`Error fetching exports for endpoint ${endpointId}: ${error.message}`);
                return [];
            });
    }

    public async getTunnelsForEndpoint(user: User, endpointId: string): Promise<TunnelRow[]> {
        console.log('====================== Fetching tunnels for endpoint: ' + endpointId);

        let metadata = await this.getAccessToken(user)
            .then((accessToken) => {
                return {
                    Authorization: `Bearer ${accessToken}`
                };
            })
            .catch((error) => {
                console.error("Error getting access token: ", error.message);
                return {};
            });

        /*
        const getRequest: UserLocator = UserLocator.create({
            userId: user?.uid,
        });

         */
        const findRequest: FindTunnelsRequest = FindTunnelsRequest.create({
            userId: user?.uid,
            endpointId: endpointId,
        });

        return await this.client
            .findTunnels(findRequest, {meta: metadata})
            .then((unaryCall) => {
                const tunnels = unaryCall.response.tunnelList;

                console.log("Tunnels for endpoint: ", tunnels);

                return tunnels.map((tunnel) => ({
                    id: tunnel.tunnelLocator?.tunnelId || '',
                    tunnelId: tunnel.tunnelLocator?.tunnelId || '',
                    connectionType: getConnectionTypeDisplayStr(tunnel?.leftConnector, tunnel?.rightConnector),
                    isActive: false,
                    left: {
                        endpointId: tunnel.leftConnector?.endpointLocator?.endpointId || '',
                        hostname: "hostname",
                        homedir: "homedir",
                        endpointIsOnline: false,
                        exportedAsset: getConnectorTypeDisplayStr(tunnel?.leftConnector),
                    },
                    right: {
                        endpointId: tunnel.rightConnector?.endpointLocator?.endpointId || '',
                        hostname: "hostname",
                        homedir: "homedir",
                        endpointIsOnline: false,
                        exportedAsset: getConnectorTypeDisplayStr(tunnel?.rightConnector),
                    },
                }));
            })
            .catch((error) => {
                console.log(`Error fetching tunnels for endpoint ${endpointId}: ${error.message}`);
                return [];
            });
    }

    public async getAllTunnels(user: User): Promise<TunnelRow[]> {
        let metadata = await this.getAccessToken(user)
            .then((accessToken) => {
                return {
                    Authorization: `Bearer ${accessToken}`
                };
            })
            .catch((error) => {
                console.error("Error getting access token: ", error.message);
                return {};
            });


        const getRequest: UserLocator = UserLocator.create({
            userId: user?.uid,
        });

        return await this.client
            .getAllTunnels(getRequest, {meta: metadata})
            .then((unaryCall) => {
                const tunnels = unaryCall.response.tunnelList;

                console.log("Tunnels for endpoint: ", tunnels);

                return tunnels.map((tunnel) => ({
                    id: tunnel.tunnelLocator?.tunnelId || '',
                    tunnelId: tunnel.tunnelLocator?.tunnelId || '',
                    connectionType: getConnectionTypeDisplayStr(tunnel?.leftConnector, tunnel?.rightConnector),
                    isActive: false,
                    left: {
                        endpointId: tunnel.leftConnector?.endpointLocator?.endpointId || '',
                        hostname: "hostname",
                        homedir: "homedir",
                        endpointIsOnline: false,
                        exportedAsset: getConnectorTypeDisplayStr(tunnel?.leftConnector),
                    },
                    right: {
                        endpointId: tunnel.rightConnector?.endpointLocator?.endpointId || '',
                        hostname: "hostname",
                        homedir: "homedir",
                        endpointIsOnline: false,
                        exportedAsset: getConnectorTypeDisplayStr(tunnel?.rightConnector),
                    },
                }));
            })
            .catch((error) => {
                console.log(`Error fetching tunnels for user ${user?.uid}: ${error.message}`);
                return [];
            });
    }

    public async deleteConnectorExport(user: User, exportId: string) {
        let metadata = await this.getAccessToken(user)
            .then((accessToken) => {
                return {
                    Authorization: `Bearer ${accessToken}`
                };
            })
            .catch((error) => {
                console.error("Error getting access token: ", error.message);
                return {};
            });


        const deleteRequest = ConnectorExportLocator.create({
            // Construct the delete request based on your API
            connectorExportId: exportId,
        });

        await this.client.deleteConnectorExport(deleteRequest, {meta: metadata})
            .then(() => {
                console.log(`Deleted export ${exportId}`);
                //getExportsForEndpoint(selectedEndpoint.id); // Refresh the exported assets list
            })
            .catch((error) => {
                console.log(`Error deleting export ${exportId}: ${error.message}`);
            });
    }

    public async deleteTunnel(user: User, tunnelId: string) {
        let metadata = await this.getAccessToken(user)
            //.then((accessToken) => "Authorization: Bearer" + accessToken)
            .then((accessToken) => {
                return {
                    Authorization: `Bearer ${accessToken}`
                };
            })
            .catch((error) => {
                console.error("Error getting access token: ", error.message);
                return {};
            });

        // Replace with the actual request to delete a tunnel
        await this.client.deleteTunnel({tunnelId}, {meta: metadata})
            .then(() => {
                console.log(`Deleted tunnel ${tunnelId}`);
                //getTunnelsForEndpoint(selectedEndpoint); // Refresh the tunnels list
            })
            .catch((error) => {
                console.log(`Error deleting tunnel ${tunnelId}: ${error.message}`);
            });
    }

    public async caffeinateEndpoint(user: User, endpointId: string, isCaffeinated: boolean) {
        let metadata = await this.getAccessToken(user)
            //.then((accessToken) => "Authorization: Bearer" + accessToken)
            .then((accessToken) => {
                return {
                    Authorization: `Bearer ${accessToken}`
                };
            })
            .catch((error) => {
                console.error("Error getting access token: ", error.message);
                return {};
            });


        const caffeinateEndpointRequest: CaffeinateEndpointRequest = CaffeinateEndpointRequest.create({
            endpointLocator: EndpointLocator.create({
                userId: user?.uid,
                endpointId: endpointId,
            }),
            isCaffeinated: !isCaffeinated,
        });

        await this.client
            .caffeinateEndpoint(caffeinateEndpointRequest, {meta: metadata})
            .then((unaryCall) => {
                console.log(`Caffeinated endpoint ${endpointId}`);
            }).catch((error) => {
                console.error(`Error caffeinating endpoint ${endpointId}: ${error.message}`);
            });
    }

    public async createConnectorExport(user: User, endpointId: string, port: number, email: string) {
        let metadata = await this.getAccessToken(user)
            //.then((accessToken) => "Authorization: Bearer" + accessToken)
            .then((accessToken) => {
                return {
                    Authorization: `Bearer ${accessToken}`
                };
            })
            .catch((error) => {
                console.error("Error getting access token: ", error.message);
                return {};
            });


        //console.log("Creating export..." + user?.uid);

        const createRequest: CreateConnectorExportRequest = CreateConnectorExportRequest.create({
            leftConnector: Connector.create({
                endpointLocator: EndpointLocator.create({
                    userId: user?.uid,
                    endpointId: endpointId,
                }),
                connectorType: {
                    oneofKind: "tcpClient",
                    tcpClient: {
                        host: "127.0.0.1",
                        port: port
                    }
                }
            }),
            connectorAccessControl: ConnectorAccessControl.create({
                email: email,
            })
        });

        await this.client.createConnectorExport(createRequest, {meta: metadata})
            .then((unaryCall) => {
                console.log("Response: ", unaryCall.response);
            })
            .catch((error) => {
                console.error("Error creating export: ", error.message);
            });
    }

    public async createTunnel(user: User, serverEndpointId: string, serverPort: number, clientEndpointId: string, clientPort: number) {
        let metadata = await this.getAccessToken(user)
            //.then((accessToken) => "Authorization: Bearer" + accessToken)
            .then((accessToken) => {
                return {
                    Authorization: `Bearer ${accessToken}`
                };
            })
            .catch((error) => {
                console.error("Error getting access token: ", error.message);
                return {};
            });


        console.log("serverEndpointId: " + serverEndpointId);
        console.log("serverPort: " + serverPort);
        console.log("clientEndpointId: " + clientEndpointId);
        console.log("clientPort: " + clientPort);
        console.log("user?.uid: " + user?.uid);

        const createRequest: CreateTunnelRequest = CreateTunnelRequest.create({
            leftConnector: Connector.create({
                endpointLocator: EndpointLocator.create({
                    userId: user?.uid,
                    endpointId: serverEndpointId,
                }),
                connectorType: {
                    oneofKind: "tcpClient",
                    tcpClient: {
                        host: "127.0.0.1",
                        port: serverPort,
                    },
                },
            }),
            rightConnector: Connector.create({
                endpointLocator: EndpointLocator.create({
                    userId: user?.uid,
                    endpointId: clientEndpointId,
                }),
                connectorType: {
                    oneofKind: "tcpServer",
                    tcpServer: {
                        host: "127.0.0.1",
                        port: clientPort,
                    },
                },
            }),
        });

        await this.client.createTunnel(createRequest, {meta: metadata})
            .then((unaryCall) => {
                console.log("Response: ", unaryCall.response);
            })
            .catch((error) => {
                console.error("Error creating tunnel: ", error);
            });
    };

    public async createSftp(
        user: User, 
        serverEndpointId: string, 
        clientPort: number, 
        clientEndpointId: string, 
        path: string, 
        accessEnum: AccessEnum, 
        authEnum: AuthEnum
    ) {
        let metadata = await this.getAccessToken(user)
            .then((accessToken) => {
                return {
                    Authorization: `Bearer ${accessToken}`
                };
            })
            .catch((error) => {
                console.error("Error getting access token: ", error.message);
                return {};
            });

        const createRequest: CreateTunnelRequest = CreateTunnelRequest.create({
            leftConnector: Connector.create({
                endpointLocator: EndpointLocator.create({
                    userId: user?.uid,
                    endpointId: serverEndpointId,
                }),
                connectorType: {
                    oneofKind: "sftpServer",
                    sftpServer: {
                        pathAccess: {
                            path: path,
                            accessEnum: accessEnum, //accessType === 'READ_ONLY' ? AccessEnum.READ_ONLY : AccessEnum.READ_WRITE,
                        },
                        authAccess: {
                            authEnum: authEnum ,
                        },
                    },
                },
            }),
            rightConnector: Connector.create({
                endpointLocator: EndpointLocator.create({
                    userId: user?.uid,
                    endpointId: clientEndpointId,
                }),
                connectorType: {
                    oneofKind: "tcpServer",
                    tcpServer: {
                        host: "127.0.0.1",
                        port: clientPort,
                    },
                },
            }),
        });

        await this.client.createTunnel(createRequest, {meta: metadata})
            .then((unaryCall) => {
                console.log("Response: ", unaryCall.response);
            })
            .catch((error) => {
                console.error("Error creating SFTP: ", error.message);
            });
    }

    public async createSsh(
        user: User,
        serverEndpointId: string,
        clientPort: number,
        clientEndpointId: string,
        authEnum: AuthEnum,
    ) {
        let metadata = await this.getAccessToken(user)
            .then((accessToken) => {
                return {
                    Authorization: `Bearer ${accessToken}`
                };
            })
            .catch((error) => {
                console.error("Error getting access token: ", error.message);
                return {};
            });

        const createRequest: CreateTunnelRequest = CreateTunnelRequest.create({
            leftConnector: Connector.create({
                endpointLocator: EndpointLocator.create({
                    userId: user?.uid,
                    endpointId: serverEndpointId,
                }),
                connectorType: {
                    oneofKind: "sshServer",
                    sshServer: {
                        authAccess: {
                            authEnum: authEnum,
                        },
                    },
                },
            }),
            rightConnector: Connector.create({
                endpointLocator: EndpointLocator.create({
                    userId: user?.uid,
                    endpointId: clientEndpointId,
                }),
                connectorType: {
                    oneofKind: "tcpServer",
                    tcpServer: {
                        host: "127.0.0.1",
                        port: clientPort,
                    },
                },
            }),
        });

        console.log("createRequest: ", createRequest);

        await this.client.createTunnel(createRequest, {meta: metadata})
            .then((unaryCall) => {
                console.log("Response: ", unaryCall.response);
            })
            .catch((error) => {
                console.error("Error creating SSH tunnel: ", error.message);
            });
    }

    public async createFileFinder(user: User, leftEndpointFilepath: EndpointFilepathRow, rightEndpointFilepath: EndpointFilepathRow) {
        console.log("Creating file finder tunnel...");

        let metadata = await this.getAccessToken(user)
            .then((accessToken) => {
                return {
                    Authorization: `Bearer ${accessToken}`
                };
            })
            .catch((error) => {
                console.error("Error getting access token: ", error.message);
                return {};
            });

        const createRequest: CreateTunnelRequest = CreateTunnelRequest.create({
            leftConnector: Connector.create({
                endpointLocator: EndpointLocator.create({
                    userId: user?.uid,
                    endpointId: leftEndpointFilepath.endpointId,
                }),
                connectorType: {
                    oneofKind: "sftpServer",
                    sftpServer: {
                        pathAccess: {
                            path: leftEndpointFilepath.path,
                            accessEnum: AccessEnum.READ_WRITE,
                        },
                        authAccess: {
                            //authEnum: AuthEnum.AUTH_NONE,
                            authEnum: AuthEnum.AUTH_USER_PUBLICKEY,
                        }
                    },
                },
            }),
            rightConnector: Connector.create({
                endpointLocator: EndpointLocator.create({
                    userId: user?.uid,
                    endpointId: rightEndpointFilepath.endpointId,
                }),
                connectorType: {
                    oneofKind: "sftpClient",
                    sftpClient: {
                        pathAccess: {
                            path: rightEndpointFilepath.path,
                            accessEnum: AccessEnum.READ_WRITE,
                        },
                    },
                },
            }),
        });

        await this.client.createTunnel(createRequest, {meta: metadata})
            .then((unaryCall) => {
                console.log("Response: ", unaryCall.response);
            })
            .catch((error) => {
                console.error("Error creating file finder tunnel: ", error.message);
            });
    }

    public async createTunnelFromExport(user: User, clientEndpointId: string, port: number, exportId: string) {
        let metadata = await this.getAccessToken(user)
            //.then((accessToken) => "Authorization: Bearer" + accessToken)
            .then((accessToken) => {
                return {
                    Authorization: `Bearer ${accessToken}`
                };
            })
            .catch((error) => {
                console.error("Error getting access token: ", error.message);
                return {};
            });


        const createRequest: CreateTunnelRequest = CreateTunnelFromExportRequest.create({
            rightConnector: Connector.create({
                endpointLocator: EndpointLocator.create({
                    userId: user?.uid,
                    endpointId: clientEndpointId,
                }),
                connectorType: {
                    oneofKind: "tcpServer",
                    tcpServer: {
                        host: "127.0.0.1",
                        port: port,
                    },
                },
            }),
            connectorExportLocator: ConnectorExportLocator.create({
                connectorExportId: exportId,
            }),
        });

        await this.client.createTunnelFromExport(createRequest, {meta: metadata})
            .then((unaryCall) => {
                console.log("Response: ", unaryCall.response);
            })
            .catch((error) => {
                console.error("Error creating tunnel: ", error.message);
            });
    }

    public async getAllConnectorExportsToMe(user: User): Promise<ExportedAssetRow[]> {
        let metadata = await this.getAccessToken(user)
            .then((accessToken) => {
                return {
                    Authorization: `Bearer ${accessToken}`
                };
            })
            .catch((error) => {
                console.error("Error getting access token: ", error.message);
                return {};
            });


        const getRequest: UserLocator = UserLocator.create({
            userId: user?.uid,
        });

        console.log("Fetching exports to me..." + user?.uid);

        return await this.client
            .getAllConnectorExportsToMe(getRequest, {meta: metadata})
            .then((unaryCall) => {
                const exportedAssets = unaryCall.response.connectorExportList;
                console.log("Exports to me: ", exportedAssets);

                return exportedAssets.map((asset) => ({
                    id: asset.connectorExportLocator?.connectorExportId || '', // Ensure id is a string
                    exportedAsset: getConnectorTypeDisplayStr(asset.connector),
                    toUser: asset.connectorAccessControl?.email || '', // Ensure toUser is a string
                    fromUserId: asset.fromUser?.userLocator?.userId || '', // Ensure fromUserId is a string
                    fromUserName: asset.fromUser?.name || '', // Ensure fromUserDisplayName is a string
                    fromUserEmail: asset.fromUser?.email || '', // Ensure fromUser is a string
                    fromUserEmailVerified: asset.fromUser?.emailVerified || false, // Ensure fromUserEmailVerified is a boolean
                    fromUserPicture: asset.fromUser?.picture || '', // Ensure fromUserPhotoURL is a string
                }));
            })
            .catch((error) => {
                console.log(`Error fetching exports to me: ${error.message}`);
                return [];
            });
    }


    public async readDir(user: User, endpointId: string, path: string): Promise<TreeNode[]> {
        console.log("Reading directory for endpoint & path: ", endpointId, path);

        let metadata = await this.getAccessToken(user)
            .then((accessToken) => {
                return {
                    Authorization: `Bearer ${accessToken}`
                };
            })
            .catch((error) => {
                console.error("Error getting access token: ", error.message);
                return {};
            });

        const readDirRequest = ReadDirRequest.create({
            endpointFilepath: EndpointFilepath.create({
                endpointLocator: EndpointLocator.create({
                    userId: user?.uid,
                    endpointId: endpointId,
                }),
                path: path,
            })
        });

        console.log("metadata: ", metadata);

        return await this.client.fsReadDir(readDirRequest, {meta: metadata})
            .then((unaryCall) => {
                //console.log("Response: ", unaryCall.response);
                const dirListing = unaryCall.response.fileInfoArr;

                return dirListing.map((fileInfo, index) => {
                    const modTime = fileInfo.modTime
                        ? new Date(Number(fileInfo.modTime.seconds) * 1000 + fileInfo.modTime.nanos / 1000000).toLocaleString()
                        : "Unknown";

                    const absolutePath = path === "/" ? `/${fileInfo.name}` : `${path}/${fileInfo.name}`;

                    return {
                        name: fileInfo.name,
                        // Create path using path + fileInfo.path
                        path: absolutePath,
                        size: Number(fileInfo.size), // Convert bigint to number
                        modTime: modTime,
                        isDir: fileInfo.isDir,
                        isDotfile: fileInfo.name.startsWith(".") ? "hidden" : "visible",
                    };
                });

            })
            .catch((error) => {
                console.error("Error reading directory: ", error.message);
                return [];
            });
    }

    public async createPath(user: User, endpointId: string, path: string): Promise<void> {
        console.log("Creating path for endpoint & path: ", endpointId, path);

        let metadata = await this.getAccessToken(user)
            .then((accessToken) => {
                return {
                    Authorization: `Bearer ${accessToken}`
                };
            })
            .catch((error) => {
                console.error("Error getting access token: ", error.message);
                return {};
            });

        const createPathRequest = CreatePathRequest.create({
            endpointFilepath: EndpointFilepath.create({
                endpointLocator: EndpointLocator.create({
                    userId: user?.uid,
                    endpointId: endpointId,
                }),
                path: path,
            })
        });

        console.log("metadata: ", metadata);

        return await this.client.fsCreatePath(createPathRequest, {meta: metadata})
            .then((unaryCall) => {
                console.log("Response: ", unaryCall.response);

                // Return empty arr since Empty is received
                return;
            })
            .catch((error) => {
                console.error("Error creating directory: ", error.message);
            });
    }

    public async trashPath(user: User, endpointId: string, path: string): Promise<void> {
        console.log("Trashing path for endpoint & path: ", endpointId, path);

        let metadata = await this.getAccessToken(user)
            .then((accessToken) => {
                return {
                    Authorization: `Bearer ${accessToken}`
                };
            })
            .catch((error) => {
                console.error("Error getting access token: ", error.message);
                return {};
            });

        const trashPathRequest = TrashPathRequest.create({
            endpointFilepath: EndpointFilepath.create({
                endpointLocator: EndpointLocator.create({
                    userId: user?.uid,
                    endpointId: endpointId,
                }),
                path: path,
            })
        });

        console.log("metadata: ", metadata);

        return await this.client.fsTrashPath(trashPathRequest, {meta: metadata})
            .then((unaryCall) => {
                console.log("Response: ", unaryCall.response);

                // Return empty arr since Empty is received
                return;
            })
            .catch((error) => {
                console.error("Error trashing path: ", error.message);
            });
    }

    public async renamePath(user: User, endpointId: string, oldPath: string, newPath: string): Promise<void> {
        console.log("Renaming path for endpoint & path: ", endpointId, oldPath, newPath);

        let metadata = await this.getAccessToken(user)
            .then((accessToken) => {
                return {
                    Authorization: `Bearer ${accessToken}`
                };
            })
            .catch((error) => {
                console.error("Error getting access token: ", error.message);
                return {};
            });

        const renamePathRequest = RenamePathRequest.create({
            endpointFilepath: EndpointFilepath.create({
                endpointLocator: EndpointLocator.create({
                    userId: user?.uid,
                    endpointId: endpointId,
                }),
                path: oldPath,
            }),
            newPath: newPath,
        });

        console.log("metadata: ", metadata);
        console.log("renamePathRequest: ", renamePathRequest);

        return await this.client.fsRenamePath(renamePathRequest, {meta: metadata})
            .then((unaryCall) => {
                console.log("Response: ", unaryCall.response);

                // Return empty arr since Empty is received
                return;
            })
            .catch((error) => {
                console.error("Error renaming path: ", error.message);
            });
    }


    public async copyPath(user: User, tunnelId: string, serverPath: string, clientPath: string, isUpload: boolean): Promise<void> {
        console.log("Copying path...");
        console.log("clientPath: ", clientPath);
        console.log("serverPath: ", serverPath);
        console.log("type: ", isUpload ? "Upload" : "Download");

        let metadata = await this.getAccessToken(user)
            .then((accessToken) => {
                return {
                    Authorization: `Bearer ${accessToken}`
                };
            })
            .catch((error) => {
                console.error("Error getting access token: ", error.message);
                return {};
            });

        const copyPathRequest = CopyPathRequest.create({
            tunnelLocator: {
                tunnelId: tunnelId,
            },
//            clientEndpointLocator: EndpointLocator.create({
//                    userId: user?.uid,
//                    endpointId: clientEndpointId,
//            }),
            serverPath: serverPath,
            clientPath: clientPath,
            isUpload: isUpload,
        });

        console.log("Copying path: ", copyPathRequest);

        await this.client.fsCopyPath(copyPathRequest, {meta: metadata})
            .then((unaryCall) => {
                console.log("Response: ", unaryCall.response);
            })
            .catch((error) => {
                console.error("Error copying path: ", error.message);
            });

        return;
    }

    public async createStripeCheckoutSession(user: User, lookupKey: string): Promise<string> {
        console.log("Creating Stripe Checkout Session for tunnelId: ", lookupKey);

        let metadata = await this.getAccessToken(user)
            .then((accessToken) => {
                return {
                    Authorization: `Bearer ${accessToken}`
                };
            })
            .catch((error) => {
                console.error("Error getting access token: ", error.message);
                return {};
            });

        const createSessionRequest = CreateStripeCheckoutSessionRequest.create({
            lookupKey: lookupKey,
        });

        return await this.client.createStripeCheckoutSession(createSessionRequest, {meta: metadata})
            .then((unaryCall) => {
                const sessionUrl = unaryCall.response.url;

                console.log("Stripe Checkout Session created: ", sessionUrl);

                return sessionUrl;
            })
            .catch((error) => {
                console.error("Error creating Stripe Checkout Session: ", error.message);
                throw error;
            });
    }

    public async getAllChat(user: User): Promise<Chat[]> {
        console.log("Fetching all chat messages...");

        let metadata = await this.getAccessToken(user)
            .then((accessToken) => {
                return {
                    Authorization: `Bearer ${accessToken}`
                };
            })
            .catch((error) => {
                console.error("Error getting access token: ", error.message);
                return {};
            });

        const getRequest: GetAllChatRequest = GetAllChatRequest.create({
            userLocator: UserLocator.create({
                userId: user?.uid,
            }),
            cursor: '',
            limit: 100,
        });

        return await this.client.getAllChat(getRequest, {meta: metadata})
            .then((unaryCall) => {
                const messages = unaryCall.response.chatList;
                console.log("Chat messages: ", messages);

                return messages.map((msg) => ({
                    id: msg.chatId || '',
                    title: msg.title || 'Unknown',
                    updatedAt: typeof msg.updatedAt === 'string'
                        ? msg.updatedAt
                        : msg.updatedAt
                            ? (msg.updatedAt.seconds ? new Date(Number(msg.updatedAt.seconds) * 1000).toISOString() : msg.updatedAt.toString())
                            : '',
                }));

            })
            .catch((error) => {
                console.log(`Error fetching chat messages: ${error.message}`);
                return [];
            });
        
    }

    public async createChat(user: User, title: string): Promise<Chat> {
        console.log("Creating chat...");
        let metadata = await this.getAccessToken(user)
            .then((accessToken) => {
                return {
                    Authorization: `Bearer ${accessToken}`
                };
            })
            .catch((error) => {
                console.error("Error getting access token: ", error.message);
                return {};
            });

        const createRequest: CreateChatRequet = CreateChatRequet.create({
            title: title,
        });

        return await this.client.createChat(createRequest, {meta: metadata})
            .then((unaryCall) => {
                const chat = unaryCall.response;
                console.log("Chat created: ", chat);

                return {
                    id: chat.chatId || '',
                    title: chat.title || 'Unknown',
                    updatedAt: typeof chat.updatedAt === 'string'
                        ? chat.updatedAt
                        : chat.updatedAt
                            ? (chat.updatedAt.seconds ? new Date(Number(chat.updatedAt.seconds) * 1000).toISOString() : chat.updatedAt.toString())
                            : '',
                };

            })
            .catch((error) => {
                console.log(`Error creating chat: ${error.message}`);
                throw error;
            });

    }

    public async deleteChat(user: User, chatId: string): Promise<void> {
        console.log("Deleting chat...");

        let metadata = await this.getAccessToken(user)
            .then((accessToken) => {
                return {
                    Authorization: `Bearer ${accessToken}`
                };
            })
            .catch((error) => {
                console.error("Error getting access token: ", error.message);
                return {};
            });

        const deleteRequest: ChatLocator = ChatLocator.create({
            userId: user?.uid,
            chatId: chatId,
        });

        return await this.client.deleteChat(deleteRequest, {meta: metadata})
            .then(() => {
                console.log("Chat deleted");
                return;
            })
            .catch((error) => {
                console.log(`Error deleting chat: ${error.message}`);
                throw error;
            });

    }

}

const getConnectorTypeDisplayStr = (connector: Connector | undefined) => {
    switch (connector?.connectorType.oneofKind) {
        case "tcpServer":
            //return `TCP Client @ ${connector.connectorType.tcpServer.host}:${connector.connectorType.tcpServer.port}`;
            return `${connector.connectorType.tcpServer.host}:${connector.connectorType.tcpServer.port}`;
        case "tcpClient":
            return `TCP Server @ ${connector.connectorType.tcpClient.host}:${connector.connectorType.tcpClient.port}`;
        case "sftpServer":
            if (connector?.connectorType.sftpServer.authAccess?.authEnum === AuthEnum.AUTH_USER_PASSWORD) {
                return "SFTP Server | Auth: Password";
            } else if (connector?.connectorType.sftpServer.authAccess?.authEnum === AuthEnum.AUTH_USER_PUBLICKEY) {
                return "SFTP Server | Auth: Public Key";
            } else if (connector?.connectorType.sftpServer.authAccess?.authEnum === AuthEnum.AUTH_CERT) {
                return "SFTP Server | Auth: Certificate";
            } else {
                return "SFTP Server";
            }
        case "sftpClient":
            return `SFTP Client`;
        case "sshServer":
            if (connector?.connectorType.sshServer.authAccess?.authEnum === AuthEnum.AUTH_USER_PASSWORD) {
                return "SSH Server | Auth: Password";
            } else if (connector?.connectorType.sshServer.authAccess?.authEnum === AuthEnum.AUTH_USER_PUBLICKEY) {
                return "SSH Server | Auth: Public Key";
            } else if (connector?.connectorType.sshServer.authAccess?.authEnum === AuthEnum.AUTH_CERT) {
                return "SSH Server | Auth: Certificate";
            } else {
                return "SSH Server";
            }
        default:
            return "Unknown type";
    }

    return "Unknown type";
};

const getConnectionTypeDisplayStr = (leftCconnector: Connector | undefined, rightConnector: Connector | undefined) => {
    if (leftCconnector?.connectorType.oneofKind === "tcpClient") {
        return `Port Forward`;
    } else if (leftCconnector?.connectorType.oneofKind === "sftpServer" &&
        rightConnector?.connectorType.oneofKind === "sftpClient") {
        return `File Finder`;
    } else if (leftCconnector?.connectorType.oneofKind === "sftpServer") {
        return `SFTP`;
    } else if (leftCconnector?.connectorType.oneofKind === "sshServer") {
        return `SSH`;
    } else {
        return "Unknown type";
    }

    return "Unknown type";
}

const formatTimeSinceLastOnline = (seconds: number): string => {
    const hrs = Math.floor(seconds / 3600);
    const mins = Math.floor((seconds % 3600) / 60);
    const secs = seconds % 60;


    let result = "";
    if (hrs > 0) result += `${hrs} hr${hrs > 1 ? 's' : ''} `;
    if (mins > 0) result += `${mins} min${mins > 1 ? 's' : ''} `;
    if (secs > 0 || result === "") result += `${secs} sec${secs > 1 ? 's' : ''}`;

    /*
    let result = "";
    if (hrs > 0) result += `${hrs}H `;
    if (mins > 0) result += `${mins} M `;
    if (secs > 0 || result === "") result += `${secs} S ago`;
     */


    return result.trim();
};


export default SpyderProxy;