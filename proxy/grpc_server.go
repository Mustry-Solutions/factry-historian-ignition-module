package main

import (
	"context"
	"log"

	pb "factry-historian-proxy/proto/historianpb"
)

// historianServer implements the HistorianCollectorServer interface.
type historianServer struct {
	pb.UnimplementedHistorianCollectorServer
}

func (s *historianServer) Store(ctx context.Context, req *pb.StoreRequest) (*pb.StoreResponse, error) {
	count := len(req.GetSamples())
	log.Printf("[gRPC] Received StoreRequest with %d samples", count)

	for i, sample := range req.GetSamples() {
		log.Printf("[gRPC]   sample[%d]: tag_path=%s timestamp_ms=%d value_int=%d value_double=%f quality=%d",
			i, sample.GetTagPath(), sample.GetTimestampMs(),
			sample.GetValueInt(), sample.GetValueDouble(), sample.GetQuality())
	}

	return &pb.StoreResponse{
		Success: true,
		Message: "stored",
		Count:   int32(count),
	}, nil
}
