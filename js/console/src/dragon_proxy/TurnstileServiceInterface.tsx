// src/dragon_proxy/TurnstileServiceInterface.tsx
//import { GrpcWebFetchTransport } from '../patch/grpc-web-transport';
import { TurnstileServiceClient } from '../proto/dragon/turnstile/v1/turnstile_service.client';
import {GrpcWebFetchTransport} from "@protobuf-ts/grpcweb-transport";

class TurnstileServiceInterface {
    private static instance: TurnstileServiceClient;

    private constructor() {}

    public static getInstance(): TurnstileServiceClient {
        if (!TurnstileServiceInterface.instance) {

            let baseUrl;
            if (window.location.hostname === 'localhost') {
                baseUrl = 'http://localhost:8081';
            } else {
                baseUrl = `${window.location.protocol}//${window.location.hostname.replace('console', 'turnstile')}`;
            }

            console.log(`turnstile baseUrl: ${baseUrl}`);

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
