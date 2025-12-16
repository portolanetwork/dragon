// src/dragon_proxy/TurnstileServiceInterface.tsx
import { TurnstileServiceClient } from '../proto/dragon/turnstile/v1/turnstile_service.client';
//import {GrpcWebFetchTransport} from "@protobuf-ts/grpcweb-transport";
import { GrpcWebFetchTransport } from '../patch/grpc-web-transport';
import { getDeploymentConfig } from './../config/deploymentConfig';

const deploymentConfig = getDeploymentConfig();

class TurnstileServiceInterface {
    private static instance: TurnstileServiceClient;

    private constructor() {}

    public static getInstance(): TurnstileServiceClient {
        if (!TurnstileServiceInterface.instance) {

            let baseUrl;
            if (window.location.hostname === 'localhost') {
                baseUrl = 'http://localhost:9091';
            } else {
                baseUrl = deploymentConfig.grpcWebUrl;
            }

            const transport = new GrpcWebFetchTransport({
                baseUrl: baseUrl,
                format: 'binary',
            });
            TurnstileServiceInterface.instance = new TurnstileServiceClient(transport);
        }
        return TurnstileServiceInterface.instance;
    }
}

export default TurnstileServiceInterface;
