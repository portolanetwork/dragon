import TurnstileServiceInterface from "./TurnstileServiceInterface";
import { User } from 'firebase/auth';
import { TurnstileServiceClient } from "../proto/dragon/turnstile/v1/turnstile_service.client";
import {
    ListMcpServersRequest,
    GetLoginStatusForMcpServerRequest,
    LoginMcpServerRequest,
    LogoutMcpServerRequest,
    McpServerLoginStatus,
    AuthType,
    RemoveMcpServerRequest,
    AddMcpServerRequest,
    TransportType,
    LoadToolsForMcpServerRequest,
    UnloadToolsForMcpServerRequest,
    GetEventLogRequest,
    EventLogList,
    Filter,
} from "../proto/dragon/turnstile/v1/turnstile_service";
import { Struct } from "../proto/google/protobuf/struct";

export interface McpServerRow {
    uuid: string;
    name: string;
    url: string;
    authType: AuthType;
    transportType: TransportType;
    hasStaticToken: boolean;
    createdAt?: string;
    updatedAt?: string;
}

export interface EventLogRow {
    id: string;
    uuid: string;
    tenant: string;
    userId: string;
    eventType: string;
    description: string;
    metadata?: any;
    createdAt: string;
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

            return unaryCall.response.mcpServer.map((server) => ({
                uuid: server.uuid,
                name: server.name,
                url: server.url,
                authType: server.authType,
                transportType: server.transportType,
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
        transportType: TransportType,
        staticToken?: string
    ): Promise<McpServerRow> {
        try {
            const accessToken = await this.getAccessToken(user);
            return await this.addMcpServerWithToken(user.uid, accessToken, name, url, authType, transportType, staticToken);
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
        transportType: TransportType,
        staticToken?: string
    ): Promise<McpServerRow> {
        try {
            const metadata = { Authorization: `Bearer ${accessToken}` };

            const unaryCall = await this.client.addMcpServer(
                AddMcpServerRequest.create({
                    name,
                    url,
                    authType,
                    transportType,
                    staticToken: staticToken || "",
                } as any),
                { meta: metadata }
            );

            const server = unaryCall.response;

            return {
                uuid: server.uuid,
                name: server.name,
                url: server.url,
                authType: server.authType,
                transportType: server.transportType,
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
            return await this.removeMcpServerWithToken(user.uid, accessToken, uuid);
        } catch (error: any) {
            console.error("Error removing MCP server: ", error.message);
            throw error;
        }
    }

    public async removeMcpServerWithToken(userId: string, accessToken: string, uuid: string): Promise<void> {
        try {
            const metadata = { Authorization: `Bearer ${accessToken}` };

            await this.client.removeMcpServer(
                RemoveMcpServerRequest.create({ uuid }),
                { meta: metadata }
            );
        } catch (error: any) {
            console.error("Error deleting MCP server: ", error.message);
            throw error;
        }
    }

    public async getLoginStatusForMcpServer(user: User, uuid: string): Promise<McpServerLoginStatus> {
        try {
            const accessToken = await this.getAccessToken(user);
            return await this.getLoginStatusForMcpServerWithToken(user.uid, accessToken, uuid);
        } catch (error: any) {
            console.error("Error getting login status for MCP server: ", error.message);
            throw error;
        }
    }

    public async getLoginStatusForMcpServerWithToken(userId: string, accessToken: string, uuid: string): Promise<McpServerLoginStatus> {
        try {
            const metadata = { Authorization: `Bearer ${accessToken}` };

            const unaryCall = await this.client.getLoginStatusForMcpServer(
                GetLoginStatusForMcpServerRequest.create({ uuid }),
                { meta: metadata }
            );

            return unaryCall.response;
        } catch (error: any) {
            console.error("Error getting login status for MCP server: ", error.message);
            throw error;
        }
    }

    public async loginMcpServer(user: User, uuid: string): Promise<string> {
        try {
            const accessToken = await this.getAccessToken(user);
            return await this.loginMcpServerWithToken(user.uid, accessToken, uuid);
        } catch (error: any) {
            console.error("Error logging in to MCP server: ", error.message);
            throw error;
        }
    }

    public async loginMcpServerWithToken(userId: string, accessToken: string, uuid: string): Promise<string> {
        try {
            const metadata = { Authorization: `Bearer ${accessToken}` };

            const unaryCall = await this.client.loginMcpServer(
                LoginMcpServerRequest.create({ uuid }),
                { meta: metadata }
            );

            return unaryCall.response.loginUrl;
        } catch (error: any) {
            console.error("Error logging in to MCP server: ", error.message);
            throw error;
        }
    }

    public async logoutMcpServer(user: User, uuid: string): Promise<void> {
        try {
            const accessToken = await this.getAccessToken(user);
            return await this.logoutMcpServerWithToken(user.uid, accessToken, uuid);
        } catch (error: any) {
            console.error("Error logging out from MCP server: ", error.message);
            throw error;
        }
    }

    public async logoutMcpServerWithToken(userId: string, accessToken: string, uuid: string): Promise<void> {
        try {
            const metadata = { Authorization: `Bearer ${accessToken}` };

            await this.client.logoutMcpServer(
                LogoutMcpServerRequest.create({ uuid }),
                { meta: metadata }
            );
        } catch (error: any) {
            console.error("Error logging out from MCP server: ", error.message);
            throw error;
        }
    }

    public async loadToolsForMcpServer(user: User, uuid: string): Promise<void> {
        try {
            const accessToken = await this.getAccessToken(user);
            return await this.loadToolsForMcpServerWithToken(user.uid, accessToken, uuid);
        } catch (error: any) {
            console.error("Error loading tools for MCP server: ", error.message);
            throw error;
        }
    }

    public async loadToolsForMcpServerWithToken(userId: string, accessToken: string, uuid: string): Promise<void> {
        try {
            const metadata = { Authorization: `Bearer ${accessToken}` };

            await this.client.loadToolsForMcpServer(
                LoadToolsForMcpServerRequest.create({ uuid }),
                { meta: metadata }
            );
        } catch (error: any) {
            console.error("Error loading tools for MCP server: ", error.message);
            throw error;
        }
    }

    public async unloadToolsForMcpServer(user: User, uuid: string): Promise<void> {
        try {
            const accessToken = await this.getAccessToken(user);
            return await this.unloadToolsForMcpServerWithToken(user.uid, accessToken, uuid);
        } catch (error: any) {
            console.error("Error unloading tools for MCP server: ", error.message);
            throw error;
        }
    }

    public async unloadToolsForMcpServerWithToken(userId: string, accessToken: string, uuid: string): Promise<void> {
        try {
            const metadata = { Authorization: `Bearer ${accessToken}` };

            await this.client.unloadToolsForMcpServer(
                UnloadToolsForMcpServerRequest.create({ uuid }),
                { meta: metadata }
            );
        } catch (error: any) {
            console.error("Error unloading tools for MCP server: ", error.message);
            throw error;
        }
    }

    public async getEventLog(user: User, eventType?: string, cursor?: string, pageSize?: number): Promise<{ events: EventLogRow[], nextCursor: string }> {
        try {
            const accessToken = await this.getAccessToken(user);
            return await this.getEventLogWithToken(user.uid, accessToken, eventType, cursor, pageSize);
        } catch (error: any) {
            console.error("Error getting event log: ", error.message);
            throw error;
        }
    }

    public async getEventLogWithToken(userId: string, accessToken: string, eventType?: string, cursor?: string, pageSize?: number): Promise<{ events: EventLogRow[], nextCursor: string }> {
        try {
            const metadata = { Authorization: `Bearer ${accessToken}` };

            const filter = eventType ? Filter.create({ eventType }) : undefined;

            const unaryCall = await this.client.getEventLog(
                GetEventLogRequest.create({
                    filter,
                    cursor: cursor || "",
                    pageSize: pageSize || 100,
                }),
                { meta: metadata }
            );

            const events = unaryCall.response.eventLog.map((event) => ({
                id: event.id.toString(),
                uuid: event.uuid,
                tenant: event.tenant,
                userId: event.userId,
                eventType: event.eventType,
                description: event.description,
                metadata: event.metadata ? Struct.toJson(event.metadata) : undefined,
                createdAt: event.createdAt
                    ? new Date(Number(event.createdAt.seconds) * 1000).toISOString()
                    : new Date().toISOString(),
            }));

            return {
                events,
                nextCursor: unaryCall.response.nextCursor,
            };
        } catch (error: any) {
            console.error("Error getting event log: ", error.message);
            throw error;
        }
    }
}

export default DragonProxy;
