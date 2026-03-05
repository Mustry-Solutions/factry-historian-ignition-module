package main

import (
	"context"
	"fmt"
	"log"
	"sync"

	"google.golang.org/grpc/metadata"
	"google.golang.org/protobuf/types/known/emptypb"
	"google.golang.org/protobuf/types/known/structpb"

	pb "factry-historian-proxy/proto/historianpb"
)

type historianServer struct {
	pb.UnimplementedHistorianServer

	mu           sync.Mutex
	measurements []*pb.Measurement
	uuidCounter  int
}

func newHistorianServer() *historianServer {
	return &historianServer{
		measurements: make([]*pb.Measurement, 0),
	}
}

func getCollectorUUID(ctx context.Context) string {
	md, ok := metadata.FromIncomingContext(ctx)
	if !ok {
		return ""
	}
	values := md.Get("collectoruuid")
	if len(values) == 0 {
		return ""
	}
	return values[0]
}

func (s *historianServer) GetCollector(ctx context.Context, _ *emptypb.Empty) (*pb.Collector, error) {
	collectorUUID := getCollectorUUID(ctx)
	log.Printf("[gRPC] GetCollector called, collectorUUID=%s", collectorUUID)
	return &pb.Collector{
		Uuid:   collectorUUID,
		Type:   "ignition",
		Status: "active",
	}, nil
}

func (s *historianServer) RegisterCollector(ctx context.Context, req *pb.RegisterCollectorSchema) (*pb.Collector, error) {
	collectorUUID := getCollectorUUID(ctx)
	log.Printf("[gRPC] RegisterCollector called, type=%s, collectorUUID=%s", req.GetCollectorType(), collectorUUID)
	return &pb.Collector{
		Uuid:         collectorUUID,
		Type:         req.GetCollectorType(),
		Buildversion: req.GetBuildVersion(),
		Status:       "active",
	}, nil
}

func (s *historianServer) CreatePoints(ctx context.Context, req *pb.Points) (*pb.CreatePointsReply, error) {
	collectorUUID := getCollectorUUID(ctx)
	count := len(req.GetPoints())
	log.Printf("[gRPC] CreatePoints: collectorUUID=%s, %d points", collectorUUID, count)

	for i, pt := range req.GetPoints() {
		ts := ""
		if pt.GetTimestamp() != nil {
			ts = pt.GetTimestamp().AsTime().UTC().Format("2006-01-02 15:04:05.000")
		}
		val := ""
		if pt.GetValue() != nil {
			switch pt.GetValue().GetKind().(type) {
			case *structpb.Value_NumberValue:
				val = fmt.Sprintf("%f", pt.GetValue().GetNumberValue())
			case *structpb.Value_BoolValue:
				val = fmt.Sprintf("%t", pt.GetValue().GetBoolValue())
			case *structpb.Value_StringValue:
				val = pt.GetValue().GetStringValue()
			default:
				val = "<null>"
			}
		}
		log.Printf("[gRPC]   point[%d]: measurementUUID=%s  time=%s  value=%s  status=%s",
			i, pt.GetMeasurementUUID(), ts, val, pt.GetStatus())
	}

	return &pb.CreatePointsReply{}, nil
}

func (s *historianServer) GetMeasurements(ctx context.Context, req *pb.MeasurementRequest) (*pb.Measurements, error) {
	collectorUUID := getCollectorUUID(ctx)
	log.Printf("[gRPC] GetMeasurements: collectorUUID=%s, requestCollectorUUID=%s", collectorUUID, req.GetCollectorUUID())

	s.mu.Lock()
	defer s.mu.Unlock()

	return &pb.Measurements{
		Measurements: s.measurements,
	}, nil
}

func (s *historianServer) CreateMeasurements(ctx context.Context, req *pb.CreateMeasurementsRequest) (*pb.CreateMeasurementsReply, error) {
	collectorUUID := getCollectorUUID(ctx)
	log.Printf("[gRPC] CreateMeasurements: collectorUUID=%s, %d measurements", collectorUUID, len(req.GetMeasurements()))

	s.mu.Lock()
	defer s.mu.Unlock()

	for _, cm := range req.GetMeasurements() {
		s.uuidCounter++
		uuid := fmt.Sprintf("meas-%06d", s.uuidCounter)
		m := &pb.Measurement{
			Uuid:     uuid,
			Name:     cm.GetName(),
			Status:   "active",
			Datatype: cm.GetDataType(),
		}
		s.measurements = append(s.measurements, m)
		log.Printf("[gRPC]   created measurement: uuid=%s, name=%s, autoOnboard=%t", uuid, cm.GetName(), cm.GetAutoOnboard())
	}

	return &pb.CreateMeasurementsReply{}, nil
}

func (s *historianServer) CreateLogs(ctx context.Context, req *pb.Logs) (*pb.CreateLogsReply, error) {
	log.Printf("[gRPC] CreateLogs: %d logs", len(req.GetLogs()))
	return &pb.CreateLogsReply{}, nil
}

func (s *historianServer) CreateStatistics(ctx context.Context, req *pb.Statistics) (*pb.CreateStatisticsReply, error) {
	log.Printf("[gRPC] CreateStatistics received")
	return &pb.CreateStatisticsReply{}, nil
}

func (s *historianServer) UpdateHealth(ctx context.Context, req *pb.HealthUpdates) (*pb.HealthUpdateReply, error) {
	log.Printf("[gRPC] UpdateHealth: %d updates", len(req.GetHealthUpdates()))
	return &pb.HealthUpdateReply{}, nil
}

func (s *historianServer) GetInfo(ctx context.Context, _ *emptypb.Empty) (*pb.HistorianInfo, error) {
	return &pb.HistorianInfo{
		Version:    "0.1.0-dev",
		ApiVersion: "1",
	}, nil
}
