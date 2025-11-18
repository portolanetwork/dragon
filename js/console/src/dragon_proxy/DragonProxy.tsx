import TurnstileServiceInterface from "./TurnstileServiceInterface";
import { User } from 'firebase/auth';
import { TurnstileServiceClient } from "../proto/dragon/turnstile/v1/turnstile_service.client";
import {
    ListMcpServersRequest,
    GetLoginStatusForMcpServerRequest,
    LoginMcpServerRequest,
    LogoutMcpServerRequest,
    McpServer,
    McpServerList,
    McpServerLoginStatus,
    McpServerLoginUrl,
    AuthType,
    LoginStatus, RemoveMcpServerRequest, AddMcpServerRequest,
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
            return await auth.getIdToken(false);
        } else {
            throw new Error("Invalid auth object. Expected a User instance.");
        }
    }

    public async listMcpServers(user: User): Promise<McpServerRow[]> {
        try {
            const accessToken = await this.getAccessToken(user);
            return await this.listMcpServersWithToken(user.uid, accessToken);
        } catch (error: any) {
            console.error("Error listing MCP servers: ", error.message);
            return [];
        }
    }

    public async listMcpServersWithToken(userId: string, accessToken: string): Promise<McpServerRow[]> {
        try {
            const metadata = { Authorization: `Bearer ${accessToken}` };

            const unaryCall = await this.client.listMcpServers(
                ListMcpServersRequest.create({ userId }),
                { meta: metadata }
            );

            console.log("MCP Server list: ", unaryCall.response.mcpServer);
            return unaryCall.response.mcpServer.map((server) => ({
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
        } catch (error: any) {
            console.error("Error listing MCP servers: ", error.message);
            return [];
        }
    }

    public async addMcpServer(
        user: User,
        name: string,
        url: string,
        authType: AuthType,
        staticToken?: string
    ): Promise<McpServerRow> {
        try {
            const accessToken = await this.getAccessToken(user);
            return await this.addMcpServerWithToken(user.uid, accessToken, name, url, authType, staticToken);
        } catch (error: any) {
            console.error("Error adding MCP server: ", error.message);
            throw error;
        }
    }

    public async addMcpServerWithToken(
        userId: string,
        accessToken: string,
        name: string,
        url: string,
        authType: AuthType,
        staticToken?: string
    ): Promise<McpServerRow> {
        try {
            const metadata = { Authorization: `Bearer ${accessToken}` };

            const unaryCall = await this.client.addMcpServer(
                AddMcpServerRequest.create({
                    name,
                    url,
                    authType,
                    staticToken: staticToken || "",
                }),
                { meta: metadata }
            );

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
        } catch (error: any) {
            console.error("Error creating MCP server: ", error.message);
            throw error;
        }
    }

    public async removeMcpServer(user: User, uuid: string): Promise<void> {
        try {
            const accessToken = await this.getAccessToken(user);
            const metadata = { Authorization: `Bearer ${accessToken}` };

            await this.client.removeMcpServer(
                RemoveMcpServerRequest.create({ uuid }),
                { meta: metadata }
            );

            console.log("MCP server deleted successfully");
        } catch (error: any) {
            console.error("Error deleting MCP server: ", error.message);
            throw error;
        }
    }

    public async getLoginStatusForMcpServer(user: User, uuid: string): Promise<McpServerLoginStatus> {
        try {
            const accessToken = await this.getAccessToken(user);
            const metadata = { Authorization: `Bearer ${accessToken}` };

            const unaryCall = await this.client.getLoginStatusForMcpServer(
                GetLoginStatusForMcpServerRequest.create({ uuid }),
                { meta: metadata }
            );

            console.log("MCP server login status: ", unaryCall.response);
            return unaryCall.response;
        } catch (error: any) {
            console.error("Error getting login status for MCP server: ", error.message);
            throw error;
        }
    }

    public async loginMcpServer(user: User, uuid: string): Promise<string> {
        try {
            const accessToken = await this.getAccessToken(user);
            const metadata = { Authorization: `Bearer ${accessToken}` };

            const unaryCall = await this.client.loginMcpServer(
                LoginMcpServerRequest.create({ uuid }),
                { meta: metadata }
            );

            console.log("MCP server login URL: ", unaryCall.response.loginUrl);
            return unaryCall.response.loginUrl;
        } catch (error: any) {
            console.error("Error logging in to MCP server: ", error.message);
            throw error;
        }
    }

    public async logoutMcpServer(user: User, uuid: string): Promise<void> {
        try {
            const accessToken = await this.getAccessToken(user);
            const metadata = { Authorization: `Bearer ${accessToken}` };

            await this.client.logoutMcpServer(
                LogoutMcpServerRequest.create({ uuid }),
                { meta: metadata }
            );

            console.log("MCP server logout successful");
        } catch (error: any) {
            console.error("Error logging out from MCP server: ", error.message);
            throw error;
        }
    }
}

export default DragonProxy;
