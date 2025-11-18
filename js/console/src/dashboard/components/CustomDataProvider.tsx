import * as React from 'react';
import {UncontrolledTreeEnvironment, Tree, StaticTreeDataProvider, TreeDataProvider, TreeItemIndex, TreeItem} from 'react-complex-tree';
import {longTree} from './treeData';
import SpyderProxy from "../../spyder_proxy/SpyderProxy";
import {User} from 'firebase/auth';

class CustomDataProvider implements TreeDataProvider {
    private data: Record<TreeItemIndex, TreeItem> = {};//{ ...longTree.items };

    private treeChangeListeners: ((changedItemIds: TreeItemIndex[]) => void)[] =
        [];

    public static async getInstance(user: User, endpointId: string): Promise<CustomDataProvider> {
        //const instance = new CustomDataProvider();
        //await instance.loadData(user, endpointId);
        //return instance;
        return new CustomDataProvider(user, endpointId);
    }

    constructor(user: User, endpointId: string) {
        this.loadData(user, endpointId);
    }

    private async loadData(user: User, endpointId: string) {
        /*
        const fetchedData = await SpyderProxy.getInstance().readDir(user, endpointId, "");

        console.log("fetchedData: ", JSON.stringify( { ...fetchedData}, null, 2));

        this.data = { ...fetchedData };

         */

        //console.log("longTree.items -- : ", JSON.stringify( { ...longTree.items }, null, 2));

        //this.data = { ...longTree.items };
    }

    public async getTreeItem(itemId: TreeItemIndex) {
        return this.data[itemId];
    }

    public async onChangeItemChildren(
        itemId: TreeItemIndex,
        newChildren: TreeItemIndex[]
    ) {
        this.data[itemId].children = newChildren;
        this.treeChangeListeners.forEach(listener => listener([itemId]));
    }

    /*
    public onDidChangeTreeData(
        listener: (changedItemIds: TreeItemIndex[]) => void
    ): Disposable {
        this.treeChangeListeners.push(listener);
        return {
            dispose: () =>
                this.treeChangeListeners.splice(
                    this.treeChangeListeners.indexOf(listener),
                    1
                ),
        };
    }

     */

    public async onRenameItem(item: TreeItem<any>, name: string): Promise<void> {
        this.data[item.index].data = name;
    }

    // custom handler for directly manipulating the tree data
    public injectItem(name: string) {
        const rand = `${Math.random()}`;
        this.data[rand] = { data: name, index: rand } as TreeItem;
        this.data.root.children?.push(rand);
        this.treeChangeListeners.forEach(listener => listener(['root']));
    }
}

export { CustomDataProvider };