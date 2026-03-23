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
	"google.golang.org/grpc/credentials/insecure"
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
	limit := flag.Int64("limit", 100, "Max points per measurement")
	uuid := flag.String("uuid", "", "Measurement UUID (overrides config)")
	plaintext := flag.Bool("plaintext", false, "Use plaintext gRPC (no TLS) for fake server")
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

	var dialOpt grpc.DialOption
	if *plaintext {
		dialOpt = grpc.WithTransportCredentials(insecure.NewCredentials())
	} else {
		dialOpt = grpc.WithTransportCredentials(credentials.NewTLS(&tls.Config{InsecureSkipVerify: true}))
	}
	conn, err := grpc.NewClient(target, dialOpt)
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

	endTs := timestamppb.New(endTime)
	resp, err := client.QueryTimeseries(ctx, &pb.QueryTimeseriesRequest{
		MeasurementUUIDs: []string{measUUID},
		Start:            timestamppb.New(startTime),
		End:              endTs,
		Limit:            limit,
	})
	if err != nil {
		log.Fatalf("QueryTimeseries failed: %v", err)
	}

	for _, series := range resp.GetSeries() {
		fmt.Printf("\nMeasurement: %s (uuid=%s, %d points)\n",
			series.GetMeasurement(), series.GetMeasurementUUID(), len(series.GetDataPoints()))
		fmt.Printf("%-30s  %s\n", "TIMESTAMP", "VALUE")
		fmt.Println("------------------------------  --------------------")
		for _, pt := range series.GetDataPoints() {
			ts := time.UnixMilli(pt.GetTimestamp()).UTC().Format("2006-01-02 15:04:05.000")
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
			fmt.Printf("%-30s  %s\n", ts, val)
		}
	}
}
