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

func saveConfig(path string, cfg *Config) error {
	data, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, append(data, '\n'), 0644)
}

func main() {
	configPath := flag.String("config", "config.json", "Path to config.json")
	name := flag.String("name", "", "Measurement name (required)")
	dataType := flag.String("type", "number", "Data type: number, boolean, string")
	flag.Parse()

	if *name == "" {
		fmt.Fprintln(os.Stderr, "Error: --name is required")
		flag.Usage()
		os.Exit(1)
	}

	cfg, err := loadConfig(*configPath)
	if err != nil {
		log.Fatalf("Failed to load config: %v", err)
	}
	if cfg.CollectorUUID == "" || cfg.Token == "" {
		log.Fatalf("collectorUUID and token must be set in %s", *configPath)
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

	// Create measurement
	log.Printf("Creating measurement '%s' with dataType=%s, autoOnboard=true", *name, *dataType)
	_, err = client.CreateMeasurements(ctx, &pb.CreateMeasurementsRequest{
		Measurements: []*pb.CreateMeasurement{
			{
				Name:        *name,
				DataType:    dataType,
				AutoOnboard: true,
			},
		},
	})
	if err != nil {
		log.Fatalf("CreateMeasurements failed: %v", err)
	}
	log.Printf("CreateMeasurements succeeded")

	// Poll GetMeasurements until the new measurement appears
	var foundUUID string
	for attempt := 1; attempt <= 5; attempt++ {
		time.Sleep(time.Duration(attempt) * 500 * time.Millisecond)

		resp, err := client.GetMeasurements(ctx, &pb.MeasurementRequest{
			CollectorUUID: cfg.CollectorUUID,
		})
		if err != nil {
			log.Fatalf("GetMeasurements failed: %v", err)
		}

		log.Printf("GetMeasurements attempt %d: %d measurements", attempt, len(resp.GetMeasurements()))
		for _, m := range resp.GetMeasurements() {
			marker := ""
			if m.GetName() == *name {
				foundUUID = m.GetUuid()
				marker = " <-- CREATED"
			}
			log.Printf("  uuid=%s  name=%s  status=%s  datatype=%s%s",
				m.GetUuid(), m.GetName(), m.GetStatus(), m.GetDatatype(), marker)
		}

		if foundUUID != "" {
			break
		}
		log.Printf("Measurement not found yet, retrying...")
	}

	if foundUUID != "" {
		cfg.MeasurementUUID = foundUUID
		if err := saveConfig(*configPath, cfg); err != nil {
			log.Fatalf("Failed to save config: %v", err)
		}
		log.Printf("Saved measurementUUID=%s to %s", foundUUID, *configPath)
	} else {
		log.Printf("WARNING: measurement '%s' not found after retries", *name)
	}
}
