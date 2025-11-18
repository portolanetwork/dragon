import TurnstileServiceInterface from "./TurnstileServiceInterface";
import { User } from 'firebase/auth';
import { TurnstileServiceClient } from "../proto/dragon/turnstile/v1/turnstile_service.client";
import {
    CreateMcpServerRequest,
    DeleteMcpServerRequest,
    ListMcpServersRequest,
    GetLoginStatusForMcpServerRequest,
    LoginMcpServerRequest,
    LogoutMcpServerRequest,
    McpServer,
    McpServerList,
    McpServerLoginStatus,
    McpServerLoginUrl,
    AuthType,
    LoginStatus,
} from "../proto/dragon/turnstile/v1/turnstile_service";

export interface McpServerRow {
    uuid: string;
    name: string;
    url: string;
    authType: AuthType;
    hasStaticToken: boolean;
    createdAt?: string;
    updatedAt?: string;
}

class DragonProxy {
    private static instance: DragonProxy;

    private client: TurnstileServiceClient = TurnstileServiceInterface.getInstance();

    // Private constructor to prevent direct instantiation
    private constructor() {
        // Initialization code here
    }

    // Static method to get the single instance of the class
    public static getInstance(): DragonProxy {
        if (!DragonProxy.instance) {
            DragonProxy.instance = new DragonProxy();
        }
        return DragonProxy.instance;
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

    public async listMcpServers(user: User): Promise<McpServerRow[]> {
        let accessToken = await this.getAccessToken(user).then((accessToken) => {
            return accessToken;
        }).catch((error) => {
            console.error("Error getting access token: ", error.message);
            return "";
        });

        let metadata = {
            Authorization: `Bearer ${accessToken}`
        }

        const listRequest: ListMcpServersRequest = ListMcpServersRequest.create({
            userId: user?.uid,
        });

        console.log("Listing MCP servers with request: ", listRequest);

        return await this.client.listMcpServers(listRequest, { meta: metadata })
            .then((unaryCall) => {
                const mcpServerList = unaryCall.response.mcpServer;
                console.log("MCP Server list: ", mcpServerList);

                return mcpServerList.map((server) => ({
                    uuid: server.uuid,
                    name: server.name,
                    url: server.url,
                    authType: server.authType,
                    hasStaticToken: server.hasStaticToken,
                    createdAt: server.createdAt
                        ? new Date(Number(server.createdAt.seconds) * 1000).toISOString()
                        : undefined,
                    updatedAt: server.updatedAt
                        ? new Date(Number(server.updatedAt.seconds) * 1000).toISOString()
                        : undefined,
                }));
            })
            .catch((error) => {
                console.error("Error listing MCP servers: ", error.message);
                return [];
            });
    }

    public async createMcpServer(
        user: User,
        name: string,
        url: string,
        authType: AuthType,
        staticToken?: string
    ): Promise<McpServerRow> {
        let accessToken = await this.getAccessToken(user).then((accessToken) => {
            return accessToken;
        }).catch((error) => {
            console.error("Error getting access token: ", error.message);
            return "";
        });

        let metadata = {
            Authorization: `Bearer ${accessToken}`
        }

        const createRequest: CreateMcpServerRequest = CreateMcpServerRequest.create({
            name: name,
            url: url,
            authType: authType,
            staticToken: staticToken || "",
        });

        console.log("Creating MCP server with request: ", createRequest);

        return await this.client.createMcpServer(createRequest, { meta: metadata })
            .then((unaryCall) => {
                const server = unaryCall.response;
                console.log("MCP server created successfully: ", server);

                return {
                    uuid: server.uuid,
                    name: server.name,
                    url: server.url,
                    authType: server.authType,
                    hasStaticToken: server.hasStaticToken,
                    createdAt: server.createdAt
                        ? new Date(Number(server.createdAt.seconds) * 1000).toISOString()
                        : undefined,
                    updatedAt: server.updatedAt
                        ? new Date(Number(server.updatedAt.seconds) * 1000).toISOString()
                        : undefined,
                };
            })
            .catch((error) => {
                console.error("Error creating MCP server: ", error.message);
                return Promise.reject(error);
            });
    }

    public async deleteMcpServer(user: User, uuid: string): Promise<void> {
        let accessToken = await this.getAccessToken(user).then((accessToken) => {
            return accessToken;
        }).catch((error) => {
            console.error("Error getting access token: ", error.message);
            return "";
        });

        let metadata = {
            Authorization: `Bearer ${accessToken}`
        }

        const deleteRequest: DeleteMcpServerRequest = DeleteMcpServerRequest.create({
            uuid: uuid,
        });

        console.log("Deleting MCP server with request: ", deleteRequest);

        return await this.client.deleteMcpServer(deleteRequest, { meta: metadata })
            .then(() => {
                console.log("MCP server deleted successfully");
            })
            .catch((error) => {
                console.error("Error deleting MCP server: ", error.message);
            });
    }

    public async getLoginStatusForMcpServer(user: User, uuid: string): Promise<McpServerLoginStatus> {
        let accessToken = await this.getAccessToken(user).then((accessToken) => {
            return accessToken;
        }).catch((error) => {
            console.error("Error getting access token: ", error.message);
            return "";
        });

        let metadata = {
            Authorization: `Bearer ${accessToken}`
        }

        const getStatusRequest: GetLoginStatusForMcpServerRequest = GetLoginStatusForMcpServerRequest.create({
            uuid: uuid,
        });

        console.log("Getting login status for MCP server: ", uuid);

        return await this.client.getLoginStatusForMcpServer(getStatusRequest, { meta: metadata })
            .then((unaryCall) => {
                const status = unaryCall.response;
                console.log("MCP server login status: ", status);
                return status;
            })
            .catch((error) => {
                console.error("Error getting login status for MCP server: ", error.message);
                return Promise.reject(error);
            });
    }

    public async loginMcpServer(user: User, uuid: string): Promise<string> {
        let accessToken = await this.getAccessToken(user).then((accessToken) => {
            return accessToken;
        }).catch((error) => {
            console.error("Error getting access token: ", error.message);
            return "";
        });

        let metadata = {
            Authorization: `Bearer ${accessToken}`
        }

        const loginRequest: LoginMcpServerRequest = LoginMcpServerRequest.create({
            uuid: uuid,
        });

        console.log("Logging in to MCP server: ", uuid);

        return await this.client.loginMcpServer(loginRequest, { meta: metadata })
            .then((unaryCall) => {
                const loginUrl = unaryCall.response.loginUrl;
                console.log("MCP server login URL: ", loginUrl);
                return loginUrl;
            })
            .catch((error) => {
                console.error("Error logging in to MCP server: ", error.message);
                return Promise.reject(error);
            });
    }

    public async logoutMcpServer(user: User, uuid: string): Promise<void> {
        let accessToken = await this.getAccessToken(user).then((accessToken) => {
            return accessToken;
        }).catch((error) => {
            console.error("Error getting access token: ", error.message);
            return "";
        });

        let metadata = {
            Authorization: `Bearer ${accessToken}`
        }

        const logoutRequest: LogoutMcpServerRequest = LogoutMcpServerRequest.create({
            uuid: uuid,
        });

        console.log("Logging out from MCP server: ", uuid);

        return await this.client.logoutMcpServer(logoutRequest, { meta: metadata })
            .then(() => {
                console.log("MCP server logout successful");
            })
            .catch((error) => {
                console.error("Error logging out from MCP server: ", error.message);
            });
    }
}

export default DragonProxy;
