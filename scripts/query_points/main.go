package main

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"os"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/metadata"
	"google.golang.org/protobuf/types/known/structpb"
	"google.golang.org/protobuf/types/known/timestamppb"

	pb "factry-historian-proxy/proto/historianpb"
)

type Config struct {
	Host            string `json:"host"`
	Port            int    `json:"port"`
	CollectorUUID   string `json:"collectorUUID"`
	Token           string `json:"token"`
	MeasurementUUID string `json:"measurementUUID"`
}

func loadConfig(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var cfg Config
	if err := json.Unmarshal(data, &cfg); err != nil {
		return nil, err
	}
	return &cfg, nil
}

func main() {
	configPath := flag.String("config", "config.json", "Path to config.json")
	startStr := flag.String("start", "", "Start time (RFC3339, default: 1 hour ago)")
	endStr := flag.String("end", "", "End time (RFC3339, default: now)")
	limit := flag.Int("limit", 100, "Max points per measurement")
	uuid := flag.String("uuid", "", "Measurement UUID (overrides config)")
	flag.Parse()

	cfg, err := loadConfig(*configPath)
	if err != nil {
		log.Fatalf("Failed to load config: %v", err)
	}
	if cfg.CollectorUUID == "" || cfg.Token == "" {
		log.Fatalf("collectorUUID and token must be set in %s", *configPath)
	}

	measUUID := cfg.MeasurementUUID
	if *uuid != "" {
		measUUID = *uuid
	}
	if measUUID == "" {
		log.Fatalf("measurementUUID is empty — use --uuid or set it in %s", *configPath)
	}

	now := time.Now()
	startTime := now.Add(-1 * time.Hour)
	endTime := now

	if *startStr != "" {
		startTime, err = time.Parse(time.RFC3339, *startStr)
		if err != nil {
			log.Fatalf("Invalid --start time: %v", err)
		}
	}
	if *endStr != "" {
		endTime, err = time.Parse(time.RFC3339, *endStr)
		if err != nil {
			log.Fatalf("Invalid --end time: %v", err)
		}
	}

	target := fmt.Sprintf("%s:%d", cfg.Host, cfg.Port)
	log.Printf("Connecting to %s", target)

	tlsCreds := credentials.NewTLS(&tls.Config{InsecureSkipVerify: true})
	conn, err := grpc.NewClient(target, grpc.WithTransportCredentials(tlsCreds))
	if err != nil {
		log.Fatalf("Failed to connect: %v", err)
	}
	defer conn.Close()

	client := pb.NewHistorianClient(conn)
	ctx := metadata.AppendToOutgoingContext(context.Background(),
		"collectoruuid", cfg.CollectorUUID,
		"authorization", "Bearer "+cfg.Token,
	)

	log.Printf("Querying: uuid=%s, start=%s, end=%s, limit=%d",
		measUUID, startTime.UTC().Format(time.RFC3339), endTime.UTC().Format(time.RFC3339), *limit)

	resp, err := client.QueryRawPoints(ctx, &pb.QueryRawPointsRequest{
		MeasurementUUIDs: []string{measUUID},
		StartTime:        timestamppb.New(startTime),
		EndTime:          timestamppb.New(endTime),
		Limit:            int32Ptr(int32(*limit)),
	})
	if err != nil {
		log.Fatalf("QueryRawPoints failed: %v", err)
	}

	for _, mp := range resp.GetMeasurementPoints() {
		fmt.Printf("\nMeasurement: %s (%d points)\n", mp.GetMeasurementUUID(), len(mp.GetPoints()))
		fmt.Printf("%-30s  %-20s  %s\n", "TIMESTAMP", "VALUE", "STATUS")
		fmt.Println("------------------------------  --------------------  ------")
		for _, pt := range mp.GetPoints() {
			ts := pt.GetTimestamp().AsTime().UTC().Format("2006-01-02 15:04:05.000")
			val := "<null>"
			if pt.GetValue() != nil {
				switch pt.GetValue().GetKind().(type) {
				case *structpb.Value_NumberValue:
					val = fmt.Sprintf("%.4f", pt.GetValue().GetNumberValue())
				case *structpb.Value_BoolValue:
					val = fmt.Sprintf("%t", pt.GetValue().GetBoolValue())
				case *structpb.Value_StringValue:
					val = pt.GetValue().GetStringValue()
				}
			}
			fmt.Printf("%-30s  %-20s  %s\n", ts, val, pt.GetStatus())
		}
	}
}

func int32Ptr(v int32) *int32 {
	return &v
}
