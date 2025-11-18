// src/types/TunnelRow.ts
export interface TunnelRow {
    id: string;
    tunnelId: string;
    connectionType: string;
    isActive: boolean;
    left: {
        endpointId: string;
        hostname: string;
        homedir: string;
        endpointIsOnline: boolean;
        exportedAsset: string;
    };
    right: {
        endpointId: string;
        hostname: string;
        homedir: string;
        endpointIsOnline: boolean;
        exportedAsset: string;
    };
}