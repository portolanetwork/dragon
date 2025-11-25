interface DeploymentConfig {
    auth0ClientId: string;
    auth0Domain: string;
}

declare global {
    interface Window {
        deploymentConfig?: DeploymentConfig;
    }
}

export const getDeploymentConfig = (): DeploymentConfig => {
    if (!window.deploymentConfig) {
        throw new Error("Deployment config is not available");
    }
    return window.deploymentConfig;
};
