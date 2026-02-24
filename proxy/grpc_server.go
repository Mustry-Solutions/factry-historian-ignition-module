package main

import (
	"context"
	"log"
	"time"

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
		ts := time.UnixMilli(sample.GetTimestampMs()).UTC().Format("2006-01-02 15:04:05.000")
		log.Printf("[gRPC]   sample[%d]: tag=%s  time=%s  value_int=%d  value_double=%f  quality=%d",
			i, sample.GetTagPath(), ts,
			sample.GetValueInt(), sample.GetValueDouble(), sample.GetQuality())
	}

	return &pb.StoreResponse{
		Success: true,
		Message: "stored",
		Count:   int32(count),
	}, nil
}
