export interface ExportedAssetRow {
    id: string;
    exportedAsset: string;
    toUser: string;
    fromUserId: string;
    fromUserName: string;
    fromUserEmail: string;
    fromUserEmailVerified: boolean;
    fromUserPicture: string;
}