package main

import (
	"context"
	"fmt"
	"log"
	"math"
	"net"
	"sync"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/metadata"
	"google.golang.org/protobuf/types/known/emptypb"
	"google.golang.org/protobuf/types/known/structpb"
	"google.golang.org/protobuf/types/known/timestamppb"

	pb "factry-historian-proxy/proto/historianpb"
)

func main() {
	port := ":9876"
	lis, err := net.Listen("tcp", port)
	if err != nil {
		log.Fatalf("Failed to listen on %s: %v", port, err)
	}

	s := grpc.NewServer()
	pb.RegisterHistorianServer(s, newHistorianServer())

	log.Printf("Fake Factry Historian gRPC server listening on %s", port)
	if err := s.Serve(lis); err != nil {
		log.Fatalf("gRPC server failed: %v", err)
	}
}

type historianServer struct {
	pb.UnimplementedHistorianServer

	mu           sync.Mutex
	measurements []*pb.Measurement
	assets       []*pb.Asset
	uuidCounter  int
}

func newHistorianServer() *historianServer {
	now := timestamppb.Now()
	s := &historianServer{
		measurements: []*pb.Measurement{
			{
				Uuid:      "pre-0001-0001-0001-000000000001",
				Name:      "Ignition-296a8ca4b6cd:[default]Sine_Wave",
				Status:    "active",
				Datatype:  "number",
				CreatedAt: now,
			},
			{
				Uuid:      "pre-0002-0002-0002-000000000002",
				Name:      "Ignition-296a8ca4b6cd:[default]Pump_Running",
				Status:    "active",
				Datatype:  "boolean",
				CreatedAt: now,
			},
			{
				Uuid:      "pre-0003-0003-0003-000000000003",
				Name:      "Ignition-296a8ca4b6cd:[default]Temperature",
				Status:    "active",
				Datatype:  "number",
				CreatedAt: now,
			},
		},
		uuidCounter: 3,
		assets: []*pb.Asset{
			{
				Uuid:      "asset-0001-0001-0001-000000000001",
				Name:      "Plant/Line1/Motor1",
				AssetPath: "Plant/Line1/Motor1",
				CreatedAt: now,
			},
			{
				Uuid:      "asset-0002-0002-0002-000000000002",
				Name:      "Plant/Line1/Motor2",
				AssetPath: "Plant/Line1/Motor2",
				CreatedAt: now,
			},
			{
				Uuid:      "asset-0003-0003-0003-000000000003",
				Name:      "Plant/Line2/Pump1",
				AssetPath: "Plant/Line2/Pump1",
				CreatedAt: now,
			},
		},
	}
	log.Printf("Pre-populated %d measurements, %d assets",
		len(s.measurements), len(s.assets))
	return s
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

func (s *historianServer) GetAssets(ctx context.Context, req *pb.GetAssetsRequest) (*pb.Assets, error) {
	collectorUUID := getCollectorUUID(ctx)
	log.Printf("[gRPC] GetAssets: collectorUUID=%s", collectorUUID)

	s.mu.Lock()
	defer s.mu.Unlock()

	return &pb.Assets{
		Assets: s.assets,
	}, nil
}

func (s *historianServer) QueryTimeseries(ctx context.Context, req *pb.QueryTimeseriesRequest) (*pb.QueryTimeseriesResponse, error) {
	collectorUUID := getCollectorUUID(ctx)
	log.Printf("[gRPC] QueryTimeseries: collectorUUID=%s, %d measurementUUIDs, aggregation=%v",
		collectorUUID, len(req.GetMeasurementUUIDs()), req.GetAggregation())

	s.mu.Lock()
	// Build lookup map: uuid -> datatype
	measByUUID := make(map[string]*pb.Measurement)
	for _, m := range s.measurements {
		measByUUID[m.GetUuid()] = m
	}
	s.mu.Unlock()

	startTime := req.GetStart().AsTime()
	endTime := startTime.Add(1 * time.Hour) // default
	if req.GetEnd() != nil {
		endTime = req.GetEnd().AsTime()
	}
	limit := int(req.GetLimit())
	if limit <= 0 {
		limit = 1000
	}

	var result []*pb.Series
	for _, uuid := range req.GetMeasurementUUIDs() {
		datatype := "number"
		measName := uuid
		if meas := measByUUID[uuid]; meas != nil {
			datatype = meas.GetDatatype()
			measName = meas.GetName()
		}

		var points []*pb.SeriesPoint
		t := startTime
		interval := 1 * time.Minute
		for t.Before(endTime) && len(points) < limit {
			pt := &pb.SeriesPoint{
				Timestamp: t.UnixMilli(),
			}

			switch datatype {
			case "boolean":
				// Alternating true/false every minute
				val := (t.Minute() % 2) == 0
				pt.Value, _ = structpb.NewValue(val)
			default:
				// Sine wave: period = 1 hour, amplitude = 100
				minutes := t.Sub(startTime).Minutes()
				v := 50 + 50*math.Sin(2*math.Pi*minutes/60.0)
				pt.Value, _ = structpb.NewValue(v)
			}

			points = append(points, pt)
			t = t.Add(interval)
		}

		series := &pb.Series{
			Measurement:     measName,
			MeasurementUUID: &uuid,
			Datatype:        datatype,
			Database:        "default",
			DataPoints:      points,
		}
		result = append(result, series)
		log.Printf("[gRPC]   uuid=%s: generated %d points (%s)", uuid, len(points), datatype)
	}

	return &pb.QueryTimeseriesResponse{Series: result}, nil
}

func (s *historianServer) GetMeasurementsByFilter(ctx context.Context, req *pb.GetMeasurementsByFilterRequest) (*pb.Measurements, error) {
	collectorUUID := getCollectorUUID(ctx)
	log.Printf("[gRPC] GetMeasurementsByFilter: collectorUUID=%s, keyword=%s", collectorUUID, req.GetKeyword())

	s.mu.Lock()
	defer s.mu.Unlock()

	return &pb.Measurements{
		Measurements: s.measurements,
	}, nil
}

func (s *historianServer) GetCollectors(ctx context.Context, req *pb.GetCollectorsRequest) (*pb.Collectors, error) {
	collectorUUID := getCollectorUUID(ctx)
	log.Printf("[gRPC] GetCollectors: collectorUUID=%s", collectorUUID)
	return &pb.Collectors{
		Collectors: []*pb.Collector{
			{
				Uuid:   collectorUUID,
				Type:   "ignition",
				Status: "active",
			},
		},
	}, nil
}

func (s *historianServer) GetTimeseriesDatabases(ctx context.Context, req *pb.GetTimeseriesDatabasesRequest) (*pb.TimeseriesDatabases, error) {
	log.Printf("[gRPC] GetTimeseriesDatabases: keyword=%s", req.GetKeyword())
	return &pb.TimeseriesDatabases{
		TimeseriesDatabases: []*pb.TimeseriesDatabase{
			{
				Uuid:        "db-0001-0001-0001-000000000001",
				Name:        "default",
				Description: "Default TimescaleDB database",
				Status:      "active",
				CreatedAt:   timestamppb.Now(),
			},
		},
	}, nil
}

func (s *historianServer) GetAssetProperties(ctx context.Context, req *pb.GetAssetPropertiesRequest) (*pb.AssetProperties, error) {
	log.Printf("[gRPC] GetAssetProperties: assetUUIDs=%v, recursive=%t", req.GetAssetUUIDs(), req.GetRecursive())
	return &pb.AssetProperties{
		AssetProperties: []*pb.AssetProperty{},
	}, nil
}
