
/*
export interface TreeNode {
    index: string;
    canMove: boolean;
    isFolder: boolean;
    children: string[] | undefined;
    data: string;
    canRename: boolean;
}

 */

export interface TreeNode {
    name: string;
    path: string;
    size: number;
    //fileMode: number;
    modTime: string;
    isDir: boolean;
}
