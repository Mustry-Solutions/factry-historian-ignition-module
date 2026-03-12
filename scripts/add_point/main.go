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
	value := flag.Float64("value", 42.0, "Value to send")
	status := flag.String("status", "Good", "Quality status: Good, Uncertain, Bad")
	plaintext := flag.Bool("plaintext", false, "Use plaintext gRPC (no TLS) for fake server")
	flag.Parse()

	cfg, err := loadConfig(*configPath)
	if err != nil {
		log.Fatalf("Failed to load config: %v", err)
	}
	if cfg.CollectorUUID == "" || cfg.Token == "" {
		log.Fatalf("collectorUUID and token must be set in %s", *configPath)
	}
	if cfg.MeasurementUUID == "" {
		log.Fatalf("measurementUUID is empty in %s — run create_measurement first", *configPath)
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

	now := time.Now()
	val, _ := structpb.NewValue(*value)

	point := &pb.Point{
		MeasurementUUID: cfg.MeasurementUUID,
		Timestamp:       timestamppb.New(now),
		Value:           val,
		Status:          *status,
	}

	log.Printf("Sending point: measurement=%s  value=%.2f  time=%s  status=%s",
		cfg.MeasurementUUID, *value, now.UTC().Format("2006-01-02 15:04:05.000"), *status)

	_, err = client.CreatePoints(ctx, &pb.Points{
		Points: []*pb.Point{point},
	})
	if err != nil {
		log.Fatalf("CreatePoints failed: %v", err)
	}
	log.Printf("CreatePoints succeeded")
}
