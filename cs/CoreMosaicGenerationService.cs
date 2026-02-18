using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;
using PhotoMosaicCreator.Core.Models;
using SixLabors.ImageSharp;
using SixLabors.ImageSharp.Formats;
using SixLabors.ImageSharp.Formats.Jpeg;
using SixLabors.ImageSharp.PixelFormats;
using SixLabors.ImageSharp.Processing;

namespace PhotoMosaicCreator.Core.Services
{
    public class CoreMosaicGenerationService
    {
        public MosaicResult GenerateMosaic(PhotoMosaicProject project, int? maxUsesOverride = null, IProgress<MosaicGenerationProgress>? progress = null, CancellationToken cancellationToken = default)
        {
            var stopwatch = Stopwatch.StartNew();
            var result = new MosaicResult();

            try
            {
                ReportProgress(progress, 0, "Validating");
                ValidateProject(project);

                ReportProgress(progress, 1, "Get Pattern");
                var pattern = GetPatternInfo(project.SelectedCellPhotoPattern);

                ReportProgress(progress, 2, "Verify Primary Image");
                var primaryPath = project.PrimaryImagePath!;
                if (!File.Exists(primaryPath))
                    throw new FileNotFoundException($"Primary image file not found: {primaryPath}");

                var primaryInfo = Image.Identify(primaryPath);
                if (primaryInfo == null)
                    throw new InvalidOperationException($"Unable to read primary image metadata: {primaryPath}");

                ReportProgress(progress, 3, "Calculating Grid");
                var grid = CalculateGrid(project, primaryInfo.Width, primaryInfo.Height, pattern);
                int targetWidth = Math.Max(1, grid.Width);
                int targetHeight = Math.Max(1, grid.Height);

                const int maxDimension = 2048;
                int maxTarget = Math.Max(targetWidth, targetHeight);
                if (maxTarget > maxDimension)
                {
                    double scale = maxDimension / (double)maxTarget;
                    targetWidth = Math.Max(1, (int)Math.Round(targetWidth * scale));
                    targetHeight = Math.Max(1, (int)Math.Round(targetHeight * scale));
                }

                targetWidth = Math.Min(targetWidth, primaryInfo.Width);
                targetHeight = Math.Min(targetHeight, primaryInfo.Height);

                var decodeOptions = new DecoderOptions
                {
                    TargetSize = new Size(targetWidth, targetHeight)
                };

                ReportProgress(progress, 4, "Loading Primary Image");
                using var primaryImage = Image.Load<Rgb24>(decodeOptions, primaryPath);
                result.GridRows = grid.Rows;
                result.GridColumns = grid.Columns;
                result.OutputWidth = grid.Width;
                result.OutputHeight = grid.Height;

                const int preProcessPercent = 10;
                int lastPreprocessReported = -1;
                IProgress<int>? preprocessProgress = null;
                if (progress != null)
                {
                    preprocessProgress = new Progress<int>(percent =>
                    {
                        int mapped = (int)Math.Round(percent * preProcessPercent / 100d);
                        if (mapped != lastPreprocessReported)
                        {
                            progress.Report(new MosaicGenerationProgress(mapped, "Loading Cells Images"));
                            lastPreprocessReported = mapped;
                        }
                    });
                }

                var cellCache = BuildCellCache(project.CellPhotos, grid, project.CellImageFitMode, pattern, preprocessProgress);
                if (cellCache.Count == 0)
                    throw new InvalidOperationException("No valid cell photos were loaded.");

                ReportProgress(progress, preProcessPercent, "Building Mosaic Plan");
                var plan = BuildMosaicPlan(grid, pattern, cellCache);
                int maxUses = maxUsesOverride ?? plan.MaxPhotoUses;

                try
                {
                    ReportProgress(progress, preProcessPercent, "Preparing Primary Image");
                    using var preparedPrimary = PreparePrimaryImage(primaryImage, grid.Width, grid.Height, project.PrimaryImageSizingMode);
                    var usageEntries = new List<CellUsage>();
                    var mosaicProgress = progress == null
                        ? null
                        : new Progress<int>(percent =>
                        {
                            int mapped = preProcessPercent + (int)Math.Round(percent * (100d - preProcessPercent) / 100d);
                            progress.Report(new MosaicGenerationProgress(mapped, "Creating Mosaic"));
                        });
                    using var mosaicImage = CreateMosaic(preparedPrimary, cellCache, grid, project, pattern, maxUses, plan.TotalCells, project.UseAllImages, usageEntries, mosaicProgress, cancellationToken);

                    var tempPath = Path.Combine(Path.GetTempPath(), $"mosaic_{Guid.NewGuid():N}.jpg");
                    mosaicImage.Save(tempPath, new JpegEncoder { Quality = 95 });
                    ReportProgress(progress, 100, "Creating Blue Overlay");
                    var overlayPath = Path.Combine(Path.GetTempPath(), $"mosaic_overlay_{Guid.NewGuid():N}.jpg");
                    preparedPrimary.Save(overlayPath, new JpegEncoder { Quality = 95 });
                    ReportProgress(progress, 100, "Overlay Image");
                    var usageReportPath = project.CreateReport
                        ? WriteUsageReportWithProgress(usageEntries, cellCache, progress)
                        : null;

                    var usedPhotoCount = usageEntries
                        .Select(entry => entry.Path)
                        .Distinct(StringComparer.OrdinalIgnoreCase)
                        .Count();

                    result.TemporaryFilePath = tempPath;
                    result.OverlayImagePath = overlayPath;
                    result.UsageReportPath = usageReportPath;
                    result.OverlayOpacityPercent = 0;
                    result.TotalCellPhotos = cellCache.Count;
                    result.UsedCellPhotos = usedPhotoCount;
                    ReportProgress(progress, 100, "Display Mosaic");
                }
                finally
                {
                    foreach (var item in cellCache)
                    {
                        if (item.ResizedLandscapeImage != null)
                            item.ResizedLandscapeImage.Dispose();
                        if (item.ResizedPortraitImage != null)
                            item.ResizedPortraitImage.Dispose();
                    }
                }

                stopwatch.Stop();
                result.GenerationTimeMs = stopwatch.ElapsedMilliseconds;
            }
            catch (OperationCanceledException)
            {
                result.ErrorMessage = "Mosaic generation cancelled.";
            }
            catch (Exception ex)
            {
                result.ErrorMessage = $"Mosaic generation failed: {ex}";
                Debug.WriteLine($"Mosaic error: {ex}");
            }

            return result;
        }

        private static void ReportProgress(IProgress<MosaicGenerationProgress>? progress, int percent, string stage)
        {
            progress?.Report(new MosaicGenerationProgress(Math.Clamp(percent, 0, 100), stage));
        }

        private static void ValidateProject(PhotoMosaicProject project)
        {
            if (string.IsNullOrWhiteSpace(project.PrimaryImagePath))
                throw new InvalidOperationException("Primary image is not selected.");
            if (project.CellPhotos.Count == 0)
                throw new InvalidOperationException("No cell photos have been added.");
            if (project.SelectedPrintSize == null)
                throw new InvalidOperationException("Print size is not selected.");
            if (project.SelectedResolution == null)
                throw new InvalidOperationException("Resolution is not selected.");
            if (project.SelectedCellSize == null)
                throw new InvalidOperationException("Cell size is not selected.");
        }

        public MosaicPlan BuildMosaicPlan(PhotoMosaicProject project)
        {
            ValidateProject(project);
            var pattern = GetPatternInfo(project.SelectedCellPhotoPattern);

            try
            {
                var primaryPath = project.PrimaryImagePath!;
                if (!File.Exists(primaryPath))
                    throw new FileNotFoundException($"Primary image file not found: {primaryPath}");

                var fileInfo = new FileInfo(primaryPath);
                Debug.WriteLine($"Loading primary image from '{primaryPath}' ({fileInfo.Length} bytes)");

                var info = Image.Identify(primaryPath);
                if (info == null)
                    throw new InvalidOperationException($"Unable to read primary image metadata: {primaryPath}");

                var grid = CalculateGrid(project, info.Width, info.Height, pattern);
                return BuildMosaicPlan(project, grid, pattern);
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Primary image load failed: {ex}");
                throw;
            }
        }

        private static MosaicPlan BuildMosaicPlan(PhotoMosaicProject project, GridDimensions grid, PatternInfo pattern)
        {
            var cellCounts = CalculateCellCounts(grid, pattern);
            var photoCounts = CountAvailablePhotos(project.CellPhotos, pattern);
            int maxUses = CalculateRecommendedMaxUses(cellCounts, photoCounts, pattern);

            return new MosaicPlan(
                cellCounts.Total,
                photoCounts.Total,
                maxUses,
                cellCounts.Landscape,
                cellCounts.Portrait,
                photoCounts.Landscape,
                photoCounts.Portrait);
        }

        private static MosaicPlan BuildMosaicPlan(GridDimensions grid, PatternInfo pattern, List<CellPhotoCache> cache)
        {
            var cellCounts = CalculateCellCounts(grid, pattern);
            var photoCounts = CountAvailablePhotos(cache, pattern);
            int maxUses = CalculateRecommendedMaxUses(cellCounts, photoCounts, pattern);

            return new MosaicPlan(
                cellCounts.Total,
                photoCounts.Total,
                maxUses,
                cellCounts.Landscape,
                cellCounts.Portrait,
                photoCounts.Landscape,
                photoCounts.Portrait);
        }

        private static CellCounts CalculateCellCounts(GridDimensions grid, PatternInfo pattern)
        {
            if (pattern.Kind == PatternKind.Parquet)
                return CountParquetCells(grid, pattern);

            int total = Math.Max(0, grid.Rows * grid.Columns);
            return pattern.Kind switch
            {
                PatternKind.Landscape => new CellCounts(total, total, 0),
                PatternKind.Portrait => new CellCounts(total, 0, total),
                _ => new CellCounts(total, 0, 0)
            };
        }

        private static PhotoCounts CountAvailablePhotos(IEnumerable<CellPhoto> photos, PatternInfo pattern)
        {
            int landscape = photos.Count(photo => photo.Orientation == PhotoOrientation.Landscape || photo.Orientation == PhotoOrientation.Square);
            int portrait = photos.Count(photo => photo.Orientation == PhotoOrientation.Portrait || photo.Orientation == PhotoOrientation.Square);

            return pattern.Kind switch
            {
                PatternKind.Landscape => new PhotoCounts(landscape, landscape, portrait),
                PatternKind.Portrait => new PhotoCounts(portrait, landscape, portrait),
                _ => new PhotoCounts(photos.Count(), landscape, portrait)
            };
        }

        private static PhotoCounts CountAvailablePhotos(IEnumerable<CellPhotoCache> cache, PatternInfo pattern)
        {
            int landscape = cache.Count(photo => photo.Orientation == PhotoOrientation.Landscape || photo.Orientation == PhotoOrientation.Square);
            int portrait = cache.Count(photo => photo.Orientation == PhotoOrientation.Portrait || photo.Orientation == PhotoOrientation.Square);
            int total = cache.Count();

            return pattern.Kind switch
            {
                PatternKind.Landscape => new PhotoCounts(total, landscape, portrait),
                PatternKind.Portrait => new PhotoCounts(total, landscape, portrait),
                _ => new PhotoCounts(total, landscape, portrait)
            };
        }

        private static int CalculateRecommendedMaxUses(CellCounts cells, PhotoCounts photos, PatternInfo pattern)
        {
            if (cells.Total <= 0 || photos.Total <= 0)
                return int.MaxValue;

            int requiredUses = pattern.Kind == PatternKind.Parquet
                ? CalculateParquetRequiredUses(cells, photos)
                : (int)Math.Ceiling(cells.Total / (double)photos.Total);

            return Math.Max(1, requiredUses * 2);
        }

        private static int CalculateParquetRequiredUses(CellCounts cells, PhotoCounts photos)
        {
            if (cells.Landscape > 0 && photos.Landscape == 0)
                return int.MaxValue;
            if (cells.Portrait > 0 && photos.Portrait == 0)
                return int.MaxValue;

            int landscapeUses = cells.Landscape > 0
                ? (int)Math.Ceiling(cells.Landscape / (double)Math.Max(1, photos.Landscape))
                : 0;
            int portraitUses = cells.Portrait > 0
                ? (int)Math.Ceiling(cells.Portrait / (double)Math.Max(1, photos.Portrait))
                : 0;

            return Math.Max(landscapeUses, portraitUses);
        }

        private static CellCounts CountParquetCells(GridDimensions grid, PatternInfo pattern)
        {
            int unitSize = grid.BaseCellPixels;
            int unitColumns = grid.UnitColumns;
            int unitRows = grid.UnitRows;

            int landscapeWidthUnits = Math.Max(1, grid.LandscapeCellWidth / unitSize);
            int landscapeHeightUnits = Math.Max(1, grid.LandscapeCellHeight / unitSize);
            int portraitWidthUnits = Math.Max(1, grid.PortraitCellWidth / unitSize);
            int portraitHeightUnits = Math.Max(1, grid.PortraitCellHeight / unitSize);

            var sequence = BuildParquetSequence(pattern);
            int deltaUnits = Math.Max(0, portraitHeightUnits - landscapeHeightUnits);
            int cycleWidthUnits = Math.Max(1, (pattern.LandscapeCount * landscapeWidthUnits) + (pattern.PortraitCount * portraitWidthUnits));
            int cyclesAcross = Math.Max(1, (unitColumns + cycleWidthUnits - 1) / cycleWidthUnits) + 1;
            int maxPortraitsPerRow = Math.Max(0, pattern.PortraitCount * cyclesAcross);
            int topPaddingUnits = deltaUnits * maxPortraitsPerRow;
            int rowCount = Math.Max(1, (unitRows + topPaddingUnits + landscapeHeightUnits - 1) / landscapeHeightUnits);
            int leftPaddingUnits = portraitWidthUnits * rowCount;
            int totalColumns = unitColumns + leftPaddingUnits + cycleWidthUnits;
            int totalRows = unitRows + topPaddingUnits + portraitHeightUnits;
            var occupied = new bool[totalRows, totalColumns];

            int totalCells = 0;
            int landscapeCells = 0;
            int portraitCells = 0;

            for (int rowIndex = 0; (rowIndex * landscapeHeightUnits) < totalRows; rowIndex++)
            {
                int baseYUnit = (rowIndex * landscapeHeightUnits) - topPaddingUnits;
                int rowOffsetUnits = -rowIndex * portraitWidthUnits;
                int xUnit = leftPaddingUnits + rowOffsetUnits;
                int patternIndex = 0;
                int currentYUnit = baseYUnit;

                while (xUnit < totalColumns)
                {
                    int yUnitForOccupancy = currentYUnit + topPaddingUnits;
                    if (yUnitForOccupancy >= totalRows || xUnit < 0)
                    {
                        xUnit++;
                        continue;
                    }

                    if (occupied[yUnitForOccupancy, xUnit])
                    {
                        xUnit++;
                        continue;
                    }

                    var orientation = sequence[patternIndex];
                    var (widthUnits, heightUnits) = orientation == PhotoOrientation.Portrait
                        ? (portraitWidthUnits, portraitHeightUnits)
                        : (landscapeWidthUnits, landscapeHeightUnits);

                    if (!CanPlace(occupied, xUnit, yUnitForOccupancy, widthUnits, heightUnits, totalColumns, totalRows))
                    {
                        xUnit++;
                        continue;
                    }

                    MarkOccupied(occupied, xUnit, yUnitForOccupancy, widthUnits, heightUnits);
                    totalCells++;
                    if (orientation == PhotoOrientation.Portrait)
                        portraitCells++;
                    else
                        landscapeCells++;

                    if (orientation == PhotoOrientation.Portrait && deltaUnits > 0)
                        currentYUnit += deltaUnits;

                    patternIndex = (patternIndex + 1) % sequence.Count;
                    xUnit += widthUnits;
                }
            }

            return new CellCounts(totalCells, landscapeCells, portraitCells);
        }

        private static GridDimensions CalculateGrid(PhotoMosaicProject project, int primaryWidth, int primaryHeight, PatternInfo pattern)
        {
            var printSize = project.SelectedPrintSize!;
            var resolution = project.SelectedResolution!;
            var cellSize = project.SelectedCellSize!;

            double printWidthIn = printSize.IsCustom ? project.CustomWidth : printSize.Width;
            double printHeightIn = printSize.IsCustom ? project.CustomHeight : printSize.Height;

            double longSide = Math.Max(printWidthIn, printHeightIn);
            double shortSide = Math.Min(printWidthIn, printHeightIn);

            var orientation = GetPhotoOrientation(primaryWidth, primaryHeight);
            if (orientation == PhotoOrientation.Landscape)
            {
                printWidthIn = longSide;
                printHeightIn = shortSide;
            }
            else if (orientation == PhotoOrientation.Portrait)
            {
                printWidthIn = shortSide;
                printHeightIn = longSide;
            }

            if (project.PrimaryImageSizingMode == PrimaryImageSizingMode.KeepAspectRatio)
            {
                double primaryAspect = primaryWidth / (double)primaryHeight;
                double printAspect = printWidthIn / printHeightIn;

                if (primaryAspect >= printAspect)
                    printHeightIn = printWidthIn / primaryAspect;
                else
                    printWidthIn = printHeightIn * primaryAspect;
            }

            int pixelWidth = Math.Max(1, (int)Math.Round(printWidthIn * resolution.PPI));
            int pixelHeight = Math.Max(1, (int)Math.Round(printHeightIn * resolution.PPI));

            double cellSizeMm = cellSize.IsCustom ? project.CustomCellSize : cellSize.SizeMm;
            double cellSizeIn = cellSizeMm / 25.4;
            int baseCellPixels = Math.Max(1, (int)Math.Round(cellSizeIn * resolution.PPI));
            var (shapeWidth, shapeHeight) = GetCellDimensions(baseCellPixels, project.CellShape);

            int landscapeWidth = shapeWidth;
            int landscapeHeight = shapeHeight;
            int portraitWidth = shapeHeight;
            int portraitHeight = shapeWidth;

            if (pattern.Kind == PatternKind.Square)
            {
                landscapeWidth = baseCellPixels;
                landscapeHeight = baseCellPixels;
                portraitWidth = baseCellPixels;
                portraitHeight = baseCellPixels;
            }

            int cellWidth = pattern.Kind == PatternKind.Portrait ? portraitWidth : landscapeWidth;
            int cellHeight = pattern.Kind == PatternKind.Portrait ? portraitHeight : landscapeHeight;

            int unitSize = GetUnitSize(landscapeWidth, landscapeHeight);
            int unitColumns = Math.Max(1, pixelWidth / unitSize);
            int unitRows = Math.Max(1, pixelHeight / unitSize);

            int columns = Math.Max(1, pixelWidth / cellWidth);
            int rows = Math.Max(1, pixelHeight / cellHeight);

            int outputWidth = pattern.Kind == PatternKind.Parquet ? unitColumns * unitSize : columns * cellWidth;
            int outputHeight = pattern.Kind == PatternKind.Parquet ? unitRows * unitSize : rows * cellHeight;

            return new GridDimensions
            {
                Width = outputWidth,
                Height = outputHeight,
                BaseCellPixels = unitSize,
                CellWidth = cellWidth,
                CellHeight = cellHeight,
                LandscapeCellWidth = landscapeWidth,
                LandscapeCellHeight = landscapeHeight,
                PortraitCellWidth = portraitWidth,
                PortraitCellHeight = portraitHeight,
                Rows = pattern.Kind == PatternKind.Parquet ? unitRows : rows,
                Columns = pattern.Kind == PatternKind.Parquet ? unitColumns : columns,
                UnitRows = unitRows,
                UnitColumns = unitColumns
            };
        }

        private static (int Width, int Height) GetCellDimensions(int baseCellPixels, CellShape shape)
        {
            return shape switch
            {
                CellShape.Rectangle4x3 => (baseCellPixels, (int)Math.Max(1, Math.Round(baseCellPixels * 3d / 4d))),
                CellShape.Rectangle3x2 => (baseCellPixels, (int)Math.Max(1, Math.Round(baseCellPixels * 2d / 3d))),
                _ => (baseCellPixels, baseCellPixels)
            };
        }

        private static int GetUnitSize(int width, int height)
        {
            int a = Math.Max(1, width);
            int b = Math.Max(1, height);
            while (b != 0)
            {
                int temp = a % b;
                a = b;
                b = temp;
            }
            return Math.Max(1, a);
        }

        private static PhotoOrientation GetPhotoOrientation(int width, int height)
        {
            if (width > height) return PhotoOrientation.Landscape;
            if (height > width) return PhotoOrientation.Portrait;
            return PhotoOrientation.Square;
        }

        private static Image<Rgb24> PreparePrimaryImage(Image<Rgb24> source, int targetWidth, int targetHeight, PrimaryImageSizingMode sizingMode)
        {
            if (source.Width == targetWidth && source.Height == targetHeight)
                return source.Clone();

            if (sizingMode == PrimaryImageSizingMode.KeepAspectRatio)
                return ResizeImage(source, targetWidth, targetHeight);

            double scale = Math.Max(targetWidth / (double)source.Width, targetHeight / (double)source.Height);
            int scaledWidth = Math.Max(1, (int)Math.Ceiling(source.Width * scale));
            int scaledHeight = Math.Max(1, (int)Math.Ceiling(source.Height * scale));

            using var scaled = ResizeImage(source, scaledWidth, scaledHeight);
            int cropX = Math.Max(0, (scaledWidth - targetWidth) / 2);
            int cropY = Math.Max(0, (scaledHeight - targetHeight) / 2);

            return scaled.Clone(ctx => ctx.Crop(new Rectangle(cropX, cropY, targetWidth, targetHeight)));
        }

        private static Image<Rgb24> PrepareCellImage(Image<Rgb24> source, int targetWidth, int targetHeight, CellImageFitMode fitMode)
        {
            if (source.Width == targetWidth && source.Height == targetHeight)
                return source.Clone();

            if (fitMode == CellImageFitMode.StretchToFit)
                return ResizeImage(source, targetWidth, targetHeight);

            double scale = Math.Max(targetWidth / (double)source.Width, targetHeight / (double)source.Height);
            int scaledWidth = Math.Max(1, (int)Math.Ceiling(source.Width * scale));
            int scaledHeight = Math.Max(1, (int)Math.Ceiling(source.Height * scale));

            using var scaled = ResizeImage(source, scaledWidth, scaledHeight);
            int cropX = Math.Max(0, (scaledWidth - targetWidth) / 2);
            int cropY = Math.Max(0, (scaledHeight - targetHeight) / 2);

            return scaled.Clone(ctx => ctx.Crop(new Rectangle(cropX, cropY, targetWidth, targetHeight)));
        }

        private static List<CellPhotoCache> BuildCellCache(List<CellPhoto> photos, GridDimensions grid, CellImageFitMode fitMode, PatternInfo pattern, IProgress<int>? progress)
        {
            var cache = new List<CellPhotoCache>();
            PhotoOrientation? requiredOrientation = pattern.Kind switch
            {
                PatternKind.Landscape => PhotoOrientation.Landscape,
                PatternKind.Portrait => PhotoOrientation.Portrait,
                _ => null
            };

            int processed = 0;
            int total = photos.Count;
            int lastReported = -1;

            foreach (var photo in photos)
            {
                if (requiredOrientation != null && photo.Orientation != requiredOrientation && photo.Orientation != PhotoOrientation.Square)
                    continue;

                try
                {
                    int targetWidth = Math.Max(1, Math.Max(grid.LandscapeCellWidth, grid.PortraitCellWidth));
                    int targetHeight = Math.Max(1, Math.Max(grid.LandscapeCellHeight, grid.PortraitCellHeight));
                    var decodeOptions = new DecoderOptions
                    {
                        TargetSize = new Size(targetWidth, targetHeight)
                    };
                    using var image = Image.Load<Rgb24>(decodeOptions, photo.Path);
                    var avgColor = GetAverageColorFast(image);

                    Image<Rgb24>? resizedLandscape = null;
                    Image<Rgb24>? resizedPortrait = null;
                    CellQuadrantColors? landscapeQuadrants = null;
                    CellQuadrantColors? portraitQuadrants = null;

                    if (photo.Orientation == PhotoOrientation.Landscape)
                    {
                        resizedLandscape = PrepareCellImage(image, grid.LandscapeCellWidth, grid.LandscapeCellHeight, fitMode);
                        landscapeQuadrants = GetQuadrantColors(resizedLandscape, 0, 0, resizedLandscape.Width, resizedLandscape.Height, clamp: false);
                    }
                    else if (photo.Orientation == PhotoOrientation.Portrait)
                    {
                        resizedPortrait = PrepareCellImage(image, grid.PortraitCellWidth, grid.PortraitCellHeight, fitMode);
                        portraitQuadrants = GetQuadrantColors(resizedPortrait, 0, 0, resizedPortrait.Width, resizedPortrait.Height, clamp: false);
                    }
                    else if (photo.Orientation == PhotoOrientation.Square)
                    {
                        resizedLandscape = PrepareCellImage(image, grid.LandscapeCellWidth, grid.LandscapeCellHeight, fitMode);
                        landscapeQuadrants = GetQuadrantColors(resizedLandscape, 0, 0, resizedLandscape.Width, resizedLandscape.Height, clamp: false);

                    resizedPortrait = PrepareCellImage(image, grid.PortraitCellWidth, grid.PortraitCellHeight, fitMode);
                    portraitQuadrants = GetQuadrantColors(resizedPortrait, 0, 0, resizedPortrait.Width, resizedPortrait.Height, clamp: false);
                    }

                    cache.Add(new CellPhotoCache
                    {
                        Path = photo.Path,
                        Orientation = photo.Orientation,
                        AverageColor = avgColor,
                        ResizedLandscapeImage = resizedLandscape,
                        ResizedPortraitImage = resizedPortrait,
                        LandscapeQuadrants = landscapeQuadrants ?? default,
                        PortraitQuadrants = portraitQuadrants ?? default,
                        UseCount = 0
                    });
                }
                catch (Exception ex)
                {
                    Debug.WriteLine($"Failed to load cell photo {photo.Path}: {ex.Message}");
                }

                processed++;
                if (total > 0 && progress != null)
                {
                    int percent = Math.Min(100, (int)Math.Round(processed * 100d / total));
                    if (percent != lastReported)
                    {
                        progress.Report(percent);
                        lastReported = percent;
                    }
                }
            }

            return cache;
        }

        private static RgbColor GetAverageColorFast(Image<Rgb24> image)
        {
            long r = 0, g = 0, b = 0;
            int pixelCount = 0;
            int step = Math.Max(1, image.Width / 50);

            image.ProcessPixelRows(accessor =>
            {
                for (int y = 0; y < accessor.Height; y += step)
                {
                    var row = accessor.GetRowSpan(y);
                    for (int x = 0; x < row.Length; x += step)
                    {
                        var pixel = row[x];
                        r += pixel.R;
                        g += pixel.G;
                        b += pixel.B;
                        pixelCount++;
                    }
                }
            });

            if (pixelCount == 0)
                return new RgbColor(128, 128, 128);

            return new RgbColor(
                (byte)(r / pixelCount),
                (byte)(g / pixelCount),
                (byte)(b / pixelCount));
        }

        private static RgbColor GetAverageColorRegionFast(Image<Rgb24> image, int rx, int ry, int rw, int rh)
        {
            int x2 = Math.Min(rx + rw, image.Width);
            int y2 = Math.Min(ry + rh, image.Height);
            int x1 = Math.Max(0, rx);
            int y1 = Math.Max(0, ry);
            int w = x2 - x1;
            int h = y2 - y1;
            if (w <= 0 || h <= 0)
                return new RgbColor(128, 128, 128);

            long r = 0, g = 0, b = 0;
            int pixelCount = 0;
            int step = Math.Max(1, w / 10);

            image.ProcessPixelRows(accessor =>
            {
                for (int y = y1; y < y2; y += step)
                {
                    var row = accessor.GetRowSpan(y);
                    for (int x = x1; x < x2; x += step)
                    {
                        var pixel = row[x];
                        r += pixel.R;
                        g += pixel.G;
                        b += pixel.B;
                        pixelCount++;
                    }
                }
            });

            if (pixelCount == 0)
                return new RgbColor(128, 128, 128);

            return new RgbColor(
                (byte)(r / pixelCount),
                (byte)(g / pixelCount),
                (byte)(b / pixelCount));
        }

        private static RgbColor GetAverageColorRegionClamped(Image<Rgb24> image, int x, int y, int width, int height)
        {
            int sampleX = Math.Max(0, x);
            int sampleY = Math.Max(0, y);
            int sampleWidth = width - Math.Max(0, -x);
            int sampleHeight = height - Math.Max(0, -y);

            if (sampleWidth <= 0 || sampleHeight <= 0)
                return new RgbColor(128, 128, 128);

            sampleWidth = Math.Min(sampleWidth, image.Width - sampleX);
            sampleHeight = Math.Min(sampleHeight, image.Height - sampleY);

            if (sampleWidth <= 0 || sampleHeight <= 0)
                return new RgbColor(128, 128, 128);

            return GetAverageColorRegionFast(image, sampleX, sampleY, sampleWidth, sampleHeight);
        }

        private static CellQuadrantColors GetQuadrantColors(Image<Rgb24> image, int x, int y, int width, int height, bool clamp)
        {
            int halfWidth = Math.Max(1, width / 2);
            int halfHeight = Math.Max(1, height / 2);
            int remainingWidth = Math.Max(1, width - halfWidth);
            int remainingHeight = Math.Max(1, height - halfHeight);

            var getColor = clamp
                ? (Func<Image<Rgb24>, int, int, int, int, RgbColor>)GetAverageColorRegionClamped
                : GetAverageColorRegionFast;

            var topLeft = getColor(image, x, y, halfWidth, halfHeight);
            var topRight = getColor(image, x + halfWidth, y, remainingWidth, halfHeight);
            var bottomLeft = getColor(image, x, y + halfHeight, halfWidth, remainingHeight);
            var bottomRight = getColor(image, x + halfWidth, y + halfHeight, remainingWidth, remainingHeight);

            return new CellQuadrantColors(topLeft, topRight, bottomLeft, bottomRight);
        }

        private static Image<Rgb24> CreateMosaic(Image<Rgb24> primary, List<CellPhotoCache> cache, GridDimensions grid, PhotoMosaicProject project, PatternInfo pattern, int maxUses, int totalCells, bool useAllImages, List<CellUsage> usage, IProgress<int>? progress, CancellationToken cancellationToken)
        {
            return pattern.Kind == PatternKind.Parquet
                ? CreateParquetMosaic(primary, cache, grid, project, pattern, maxUses, totalCells, useAllImages, usage, progress, cancellationToken)
                : CreateStandardMosaic(primary, cache, grid, project, pattern, maxUses, totalCells, useAllImages, usage, progress, cancellationToken);
        }

        private static Image<Rgb24> CreateStandardMosaic(Image<Rgb24> primary, List<CellPhotoCache> cache, GridDimensions grid, PhotoMosaicProject project, PatternInfo pattern, int maxUses, int totalCells, bool useAllImages, List<CellUsage> usage, IProgress<int>? progress, CancellationToken cancellationToken)
        {
            var mosaic = new Image<Rgb24>(grid.Width, grid.Height);

            int colorAdjustPercent = GetColorAdjustPercent(project);
            int minSpacing = project.SelectedDuplicateSpacing?.MinSpacing ?? 0;
            var lastUsedPositions = new Dictionary<string, List<(int Row, int Col)>>();

            PhotoOrientation? requiredOrientation = pattern.Kind switch
            {
                PatternKind.Landscape => PhotoOrientation.Landscape,
                PatternKind.Portrait => PhotoOrientation.Portrait,
                _ => null
            };

            int randomCellCandidates = Math.Clamp(project.RandomCellCandidates, 1, 20);
            var placements = new List<MosaicPlacement>(grid.Rows * grid.Columns);
            for (int row = 0; row < grid.Rows; row++)
            {
                for (int col = 0; col < grid.Columns; col++)
                {
                    int x = col * grid.CellWidth;
                    int y = row * grid.CellHeight;
                    var targetColor = GetAverageColorRegionFast(primary, x, y, grid.CellWidth, grid.CellHeight);
                    var targetQuadrants = GetQuadrantColors(primary, x, y, grid.CellWidth, grid.CellHeight, clamp: false);
                    placements.Add(new MosaicPlacement(row, col, x, y, grid.CellWidth, grid.CellHeight, requiredOrientation ?? PhotoOrientation.Landscape, targetColor, targetQuadrants));
                }
            }

            var availablePlacements = placements;
            if (useAllImages)
            {
                availablePlacements = [.. placements];
                foreach (var item in cache)
                {
                    cancellationToken.ThrowIfCancellationRequested();
                    if (availablePlacements.Count == 0) break;
                    if (item.UseCount >= maxUses) continue;

                    int bestIndex = -1;
                    double bestDistance = double.MaxValue;
                    for (int i = 0; i < availablePlacements.Count; i++)
                    {
                        var placement = availablePlacements[i];
                        double distance = QuadrantDistance(GetQuadrants(item, placement.Orientation), placement.TargetQuadrants);
                        if (distance < bestDistance)
                        {
                            bestDistance = distance;
                            bestIndex = i;
                        }
                    }

                    if (bestIndex < 0) continue;

                    var selectedPlacement = availablePlacements[bestIndex];
                    PlaceCell(mosaic, item, selectedPlacement, colorAdjustPercent);
                    TrackUse(item, lastUsedPositions, usage, selectedPlacement.Row, selectedPlacement.Col, selectedPlacement.X, selectedPlacement.Y);
                    availablePlacements.RemoveAt(bestIndex);
                }
            }

            Shuffle(availablePlacements);

            int processed = 0;
            int lastReported = -1;

            foreach (var placement in availablePlacements)
            {
                cancellationToken.ThrowIfCancellationRequested();
                var match = FindBestMatch(cache, placement.TargetQuadrants, maxUses, minSpacing, placement.Row, placement.Col, randomCellCandidates, lastUsedPositions, requiredOrientation);
                if (match == null) continue;

                PlaceCell(mosaic, match, placement, colorAdjustPercent);
                TrackUse(match, lastUsedPositions, usage, placement.Row, placement.Col, placement.X, placement.Y);

                processed++;
                if (totalCells > 0)
                {
                    int percent = Math.Min(100, (int)Math.Round(processed * 100d / totalCells));
                    if (percent != lastReported)
                    {
                        progress?.Report(percent);
                        lastReported = percent;
                    }
                }
            }

            return mosaic;
        }

        private static Image<Rgb24> CreateParquetMosaic(Image<Rgb24> primary, List<CellPhotoCache> cache, GridDimensions grid, PhotoMosaicProject project, PatternInfo pattern, int maxUses, int totalCells, bool useAllImages, List<CellUsage> usage, IProgress<int>? progress, CancellationToken cancellationToken)
        {
            var mosaic = new Image<Rgb24>(grid.Width, grid.Height);

            int colorAdjustPercent = GetColorAdjustPercent(project);
            int minSpacing = project.SelectedDuplicateSpacing?.MinSpacing ?? 0;
            var lastUsedPositions = new Dictionary<string, List<(int Row, int Col)>>();

            int unitSize = grid.BaseCellPixels;
            int unitColumns = grid.UnitColumns;
            int unitRows = grid.UnitRows;

            int landscapeWidthUnits = Math.Max(1, grid.LandscapeCellWidth / unitSize);
            int landscapeHeightUnits = Math.Max(1, grid.LandscapeCellHeight / unitSize);
            int portraitWidthUnits = Math.Max(1, grid.PortraitCellWidth / unitSize);
            int portraitHeightUnits = Math.Max(1, grid.PortraitCellHeight / unitSize);

            var sequence = BuildParquetSequence(pattern);
            int deltaUnits = Math.Max(0, portraitHeightUnits - landscapeHeightUnits);
            int cycleWidthUnits = Math.Max(1, (pattern.LandscapeCount * landscapeWidthUnits) + (pattern.PortraitCount * portraitWidthUnits));
            int cyclesAcross = Math.Max(1, (unitColumns + cycleWidthUnits - 1) / cycleWidthUnits) + 1;
            int maxPortraitsPerRow = Math.Max(0, pattern.PortraitCount * cyclesAcross);
            int topPaddingUnits = deltaUnits * maxPortraitsPerRow;
            int rowCount = Math.Max(1, (unitRows + topPaddingUnits + landscapeHeightUnits - 1) / landscapeHeightUnits);
            int leftPaddingUnits = portraitWidthUnits * rowCount;
            int totalColumns = unitColumns + leftPaddingUnits + cycleWidthUnits;
            int tRows = unitRows + topPaddingUnits + portraitHeightUnits;
            var occupied = new bool[tRows, totalColumns];
            var plannedOccupied = new bool[tRows, totalColumns];
            int randomCellCandidates = Math.Clamp(project.RandomCellCandidates, 1, 20);
            var placements = new List<MosaicPlacement>();

            int processed = 0;
            int lastReported = -1;

            for (int rowIndex = 0; (rowIndex * landscapeHeightUnits) < tRows; rowIndex++)
            {
                int baseYUnit = (rowIndex * landscapeHeightUnits) - topPaddingUnits;
                int rowOffsetUnits = -rowIndex * portraitWidthUnits;
                int xUnit = leftPaddingUnits + rowOffsetUnits;
                int patternIndex = 0;
                int currentYUnit = baseYUnit;

                while (xUnit < totalColumns)
                {
                    int yUnitForOccupancy = currentYUnit + topPaddingUnits;
                    if (yUnitForOccupancy >= tRows || xUnit < 0)
                    {
                        xUnit++;
                        continue;
                    }

                    var orient = sequence[patternIndex];
                    var (widthUnits, heightUnits) = orient == PhotoOrientation.Portrait
                        ? (portraitWidthUnits, portraitHeightUnits)
                        : (landscapeWidthUnits, landscapeHeightUnits);

                    if (!CanPlace(plannedOccupied, xUnit, yUnitForOccupancy, widthUnits, heightUnits, totalColumns, tRows))
                    {
                        xUnit++;
                        continue;
                    }

                    MarkOccupied(plannedOccupied, xUnit, yUnitForOccupancy, widthUnits, heightUnits);
                    int x = (xUnit - leftPaddingUnits) * unitSize;
                    int y = currentYUnit * unitSize;
                    int w = widthUnits * unitSize;
                    int h = heightUnits * unitSize;
                    var targetColor = GetAverageColorRegionClamped(primary, x, y, w, h);
                    var targetQuadrants = GetQuadrantColors(primary, x, y, w, h, clamp: true);
                    placements.Add(new MosaicPlacement(yUnitForOccupancy, xUnit, x, y, w, h, orient, targetColor, targetQuadrants));

                    if (orient == PhotoOrientation.Portrait && deltaUnits > 0)
                        currentYUnit += deltaUnits;

                    patternIndex = (patternIndex + 1) % sequence.Count;
                    xUnit += widthUnits;
                }
            }

            var availablePlacements = placements;
            if (useAllImages)
            {
                availablePlacements = [.. placements];
                foreach (var item in cache)
                {
                    cancellationToken.ThrowIfCancellationRequested();
                    if (availablePlacements.Count == 0) break;
                    if (item.UseCount >= maxUses) continue;

                    int bestIndex = -1;
                    double bestDistance = double.MaxValue;
                    for (int i = 0; i < availablePlacements.Count; i++)
                    {
                        var placement = availablePlacements[i];
                        if (!IsOrientationCompatible(item.Orientation, placement.Orientation)) continue;

                        double distance = QuadrantDistance(GetQuadrants(item, placement.Orientation), placement.TargetQuadrants);
                        if (distance < bestDistance)
                        {
                            bestDistance = distance;
                            bestIndex = i;
                        }
                    }

                    if (bestIndex < 0) continue;

                    var selectedPlacement = availablePlacements[bestIndex];
                    if (PlaceParquetCell(mosaic, item, selectedPlacement, colorAdjustPercent, occupied, unitSize, totalColumns, tRows))
                    {
                        TrackUse(item, lastUsedPositions, usage, selectedPlacement.Row, selectedPlacement.Col, selectedPlacement.X, selectedPlacement.Y);
                    }
                    availablePlacements.RemoveAt(bestIndex);
                }
            }

            Shuffle(availablePlacements);

            foreach (var placement in availablePlacements)
            {
                cancellationToken.ThrowIfCancellationRequested();

                if (!TryPlaceParquetCell(cache, mosaic, lastUsedPositions, usage, minSpacing, maxUses, colorAdjustPercent,
                    occupied, unitSize, totalColumns, tRows, placement, randomCellCandidates))
                {
                    continue;
                }

                processed++;
                if (totalCells > 0)
                {
                    int percent = Math.Min(100, (int)Math.Round(processed * 100d / totalCells));
                    if (percent != lastReported)
                    {
                        progress?.Report(percent);
                        lastReported = percent;
                    }
                }
            }

            return mosaic;
        }

        private static bool TryPlaceParquetCell(
            List<CellPhotoCache> cache,
            Image<Rgb24> mosaic,
            Dictionary<string, List<(int Row, int Col)>> lastUsedPositions,
            List<CellUsage> usage,
            int minSpacing,
            int maxUses,
            int colorAdjustPercent,
            bool[,] occupied,
            int unitSize,
            int unitColumns,
            int unitRows,
            MosaicPlacement placement,
            int randomCellCandidates)
        {
            int widthUnits = Math.Max(1, placement.Width / unitSize);
            int heightUnits = Math.Max(1, placement.Height / unitSize);

            if (!CanPlace(occupied, placement.Col, placement.Row, widthUnits, heightUnits, unitColumns, unitRows))
                return false;

            var match = FindBestMatch(cache, placement.TargetQuadrants, maxUses, minSpacing, placement.Row, placement.Col, randomCellCandidates, lastUsedPositions, placement.Orientation);
            if (match == null)
                return false;

            PlaceCell(mosaic, match, placement, colorAdjustPercent);
            MarkOccupied(occupied, placement.Col, placement.Row, widthUnits, heightUnits);
            TrackUse(match, lastUsedPositions, usage, placement.Row, placement.Col, placement.X, placement.Y);

            return true;
        }

        private static CellPhotoCache? FindBestMatch(
            List<CellPhotoCache> cache,
            CellQuadrantColors target,
            int maxUses,
            int minSpacing,
            int row,
            int col,
            int candidateCount,
            Dictionary<string, List<(int Row, int Col)>> lastUsedPositions,
            PhotoOrientation? requiredOrientation)
        {
            candidateCount = Math.Max(1, candidateCount);
            var candidates = new List<(CellPhotoCache Item, double Distance)>();

            foreach (var item in cache)
            {
                if (item.UseCount >= maxUses) continue;
                if (requiredOrientation != null && item.Orientation != requiredOrientation && item.Orientation != PhotoOrientation.Square) continue;

                if (minSpacing > 0 && lastUsedPositions.TryGetValue(item.Path, out var positions))
                {
                    if (positions.Any(position =>
                        Math.Abs(position.Row - row) <= minSpacing && Math.Abs(position.Col - col) <= minSpacing))
                    {
                        continue;
                    }
                }

                double distance = QuadrantDistance(GetQuadrants(item, requiredOrientation ?? PhotoOrientation.Landscape), target);
                candidates.Add((item, distance));
            }

            if (candidates.Count == 0)
                return null;

            var ordered = candidates.OrderBy(c => c.Distance).Take(candidateCount).ToList();
            return ordered[Random.Shared.Next(ordered.Count)].Item;
        }

        private static double ColorDistance(RgbColor c1, RgbColor c2)
        {
            double dr = c1.R - c2.R;
            double dg = c1.G - c2.G;
            double db = c1.B - c2.B;
            return Math.Sqrt(dr * dr + dg * dg + db * db);
        }

        private static double QuadrantDistance(CellQuadrantColors source, CellQuadrantColors target)
        {
            return ColorDistance(source.TopLeft, target.TopLeft)
                + ColorDistance(source.TopRight, target.TopRight)
                + ColorDistance(source.BottomLeft, target.BottomLeft)
                + ColorDistance(source.BottomRight, target.BottomRight);
        }

        private static CellQuadrantColors GetQuadrants(CellPhotoCache cache, PhotoOrientation orientation)
        {
            return orientation == PhotoOrientation.Portrait
                ? cache.PortraitQuadrants
                : cache.LandscapeQuadrants;
        }

        private static Image<Rgb24> ResizeImage(Image<Rgb24> original, int width, int height)
        {
            return original.Clone(ctx => ctx.Resize(width, height, KnownResamplers.Bicubic));
        }

        private static int GetColorAdjustPercent(PhotoMosaicProject project)
        {
            int percent = project.SelectedColorChange?.PercentageChange ?? 0;
            if (project.SelectedColorChange?.IsCustom == true)
                percent = project.CustomColorChange;
            return Math.Clamp(percent, 0, 100);
        }

        public static Image<Rgb24> BlurImage(Image<Rgb24> source, int radius)
        {
            if (radius <= 0)
                return source.Clone();

            const int maxBlurDimension = 1024;
            int maxSourceDimension = Math.Max(source.Width, source.Height);
            Image<Rgb24> workingImage = source;

            if (maxSourceDimension > maxBlurDimension)
            {
                double scale = maxBlurDimension / (double)maxSourceDimension;
                int targetWidth = Math.Max(1, (int)Math.Round(source.Width * scale));
                int targetHeight = Math.Max(1, (int)Math.Round(source.Height * scale));
                workingImage = ResizeImage(source, targetWidth, targetHeight);
            }

            try
            {
                float sigma = Math.Max(0.5f, radius / 3f);
                return workingImage.Clone(ctx => ctx.GaussianBlur(sigma));
            }
            finally
            {
                if (!ReferenceEquals(workingImage, source))
                {
                    workingImage.Dispose();
                }
            }
        }

        private static void ApplyColorAdjustment(Image<Rgb24> image, RgbColor target, int percent)
        {
            float factor = percent / 100f;
            if (factor <= 0) return;

            image.ProcessPixelRows(accessor =>
            {
                for (int y = 0; y < accessor.Height; y++)
                {
                    var row = accessor.GetRowSpan(y);
                    for (int x = 0; x < row.Length; x++)
                    {
                        var pixel = row[x];
                        row[x] = new Rgb24(
                            (byte)Math.Clamp((int)(pixel.R * (1 - factor) + target.R * factor), 0, 255),
                            (byte)Math.Clamp((int)(pixel.G * (1 - factor) + target.G * factor), 0, 255),
                            (byte)Math.Clamp((int)(pixel.B * (1 - factor) + target.B * factor), 0, 255));
                    }
                }
            });
        }

        private static void PlaceCell(Image<Rgb24> mosaic, CellPhotoCache match, MosaicPlacement placement, int colorAdjustPercent)
        {
            using var cell = GetCellImage(match, placement.Orientation).Clone();
            if (colorAdjustPercent > 0)
                ApplyColorAdjustment(cell, placement.TargetColor, colorAdjustPercent);

            var destPoint = new Point(placement.X, placement.Y);
            mosaic.Mutate(ctx => ctx.DrawImage(cell, destPoint, 1f));
        }

        private static bool PlaceParquetCell(Image<Rgb24> mosaic, CellPhotoCache match, MosaicPlacement placement, int colorAdjustPercent, bool[,] occupied, int unitSize, int unitColumns, int unitRows)
        {
            int widthUnits = Math.Max(1, placement.Width / unitSize);
            int heightUnits = Math.Max(1, placement.Height / unitSize);

            if (!CanPlace(occupied, placement.Col, placement.Row, widthUnits, heightUnits, unitColumns, unitRows))
                return false;

            PlaceCell(mosaic, match, placement, colorAdjustPercent);
            MarkOccupied(occupied, placement.Col, placement.Row, widthUnits, heightUnits);
            return true;
        }

        private static bool IsOrientationCompatible(PhotoOrientation photoOrientation, PhotoOrientation requiredOrientation)
        {
            return requiredOrientation switch
            {
                PhotoOrientation.Landscape => photoOrientation == PhotoOrientation.Landscape || photoOrientation == PhotoOrientation.Square,
                PhotoOrientation.Portrait => photoOrientation == PhotoOrientation.Portrait || photoOrientation == PhotoOrientation.Square,
                _ => true
            };
        }

        private static List<PhotoOrientation> BuildParquetSequence(PatternInfo pattern)
        {
            int landscapeCount = Math.Max(1, pattern.LandscapeCount);
            int portraitCount = Math.Max(1, pattern.PortraitCount);

            var sequence = new List<PhotoOrientation>(landscapeCount + portraitCount);
            for (int i = 0; i < landscapeCount; i++)
                sequence.Add(PhotoOrientation.Landscape);
            for (int i = 0; i < portraitCount; i++)
                sequence.Add(PhotoOrientation.Portrait);
            return sequence;
        }

        private static bool CanPlace(bool[,] occupied, int xUnit, int yUnit, int widthUnits, int heightUnits, int maxColumns, int maxRows)
        {
            if (xUnit + widthUnits > maxColumns || yUnit + heightUnits > maxRows)
                return false;

            for (int y = yUnit; y < yUnit + heightUnits; y++)
                for (int x = xUnit; x < xUnit + widthUnits; x++)
                    if (occupied[y, x])
                        return false;
            return true;
        }

        private static void MarkOccupied(bool[,] occupied, int xUnit, int yUnit, int widthUnits, int heightUnits)
        {
            for (int y = yUnit; y < yUnit + heightUnits; y++)
                for (int x = xUnit; x < xUnit + widthUnits; x++)
                    occupied[y, x] = true;
        }

        private static void TrackUse(CellPhotoCache match, Dictionary<string, List<(int Row, int Col)>> lastUsedPositions, List<CellUsage> usage, int row, int col, int x, int y)
        {
            match.UseCount++;
            if (!lastUsedPositions.TryGetValue(match.Path, out var positions))
            {
                positions = [];
                lastUsedPositions[match.Path] = positions;
            }
            positions.Add((row, col));
            usage.Add(new CellUsage(match.Path, x, y));
        }

        private static string WriteUsageReportWithProgress(List<CellUsage> usage, List<CellPhotoCache> cache, IProgress<MosaicGenerationProgress>? progress)
        {
            ReportProgress(progress, 100, "Writing Report");
            return WriteUsageReport(usage, cache);
        }

        private static string WriteUsageReport(List<CellUsage> usage, List<CellPhotoCache> cache)
        {
            var reportPath = Path.Combine(Path.GetTempPath(), $"mosaic_usage_{Guid.NewGuid():N}.csv");
            var usageLookup = usage.GroupBy(entry => entry.Path)
                .ToDictionary(group => group.Key, group => group.ToList());

            using var writer = new StreamWriter(reportPath);
            writer.WriteLine("Name,UseCount,X,Y");

            foreach (var item in cache)
            {
                var name = EscapeCsv(Path.GetFileName(item.Path));
                if (!usageLookup.TryGetValue(item.Path, out var entries) || entries.Count == 0)
                {
                    writer.WriteLine($"{name},{item.UseCount},,");
                    continue;
                }

                foreach (var entry in entries)
                    writer.WriteLine($"{name},{item.UseCount},{entry.X},{entry.Y}");
            }

            return reportPath;
        }

        private static string EscapeCsv(string value)
        {
            return $"\"{value.Replace("\"", "\"\"")}\"";
        }

        private static Image<Rgb24> GetCellImage(CellPhotoCache cache, PhotoOrientation orientation)
        {
            var image = orientation == PhotoOrientation.Portrait
                ? cache.ResizedPortraitImage
                : cache.ResizedLandscapeImage;
            return image
                ?? cache.ResizedLandscapeImage
                ?? cache.ResizedPortraitImage
                ?? throw new InvalidOperationException("Cell image cache is empty.");
        }

        private static void Shuffle<T>(IList<T> items)
        {
            for (int i = items.Count - 1; i > 0; i--)
            {
                int swapIndex = Random.Shared.Next(i + 1);
                (items[i], items[swapIndex]) = (items[swapIndex], items[i]);
            }
        }

        private static PatternInfo GetPatternInfo(CellPhotoPatternConfig? pattern)
        {
            var name = pattern?.Name ?? "Square";

            if (name.Equals("Landscape", StringComparison.OrdinalIgnoreCase))
                return new PatternInfo(PatternKind.Landscape, 1, 0);

            if (name.Equals("Portrait", StringComparison.OrdinalIgnoreCase))
                return new PatternInfo(PatternKind.Portrait, 0, 1);

            if (name.StartsWith("Parquet", StringComparison.OrdinalIgnoreCase))
            {
                var match = Regex.Match(name, @"(\d+)\s*L\s*(\d+)\s*P", RegexOptions.IgnoreCase);
                if (match.Success)
                {
                    return new PatternInfo(
                        PatternKind.Parquet,
                        int.Parse(match.Groups[1].Value),
                        int.Parse(match.Groups[2].Value));
                }
                return new PatternInfo(PatternKind.Parquet, 1, 1);
            }

            return new PatternInfo(PatternKind.Square, 0, 0);
        }

        // Internal types
        private enum PatternKind { Square, Landscape, Portrait, Parquet }
        private readonly record struct PatternInfo(PatternKind Kind, int LandscapeCount, int PortraitCount);
        private readonly record struct MosaicPlacement(int Row, int Col, int X, int Y, int Width, int Height, PhotoOrientation Orientation, RgbColor TargetColor, CellQuadrantColors TargetQuadrants);
        private readonly record struct CellUsage(string Path, int X, int Y);
        private readonly record struct CellQuadrantColors(RgbColor TopLeft, RgbColor TopRight, RgbColor BottomLeft, RgbColor BottomRight);
        private readonly record struct CellCounts(int Total, int Landscape, int Portrait);
        private readonly record struct PhotoCounts(int Total, int Landscape, int Portrait);

        public sealed record MosaicPlan(
            int TotalCells,
            int AvailablePhotos,
            int MaxPhotoUses,
            int LandscapeCells,
            int PortraitCells,
            int AvailableLandscapePhotos,
            int AvailablePortraitPhotos);

        private class GridDimensions
        {
            public int Width { get; set; }
            public int Height { get; set; }
            public int BaseCellPixels { get; set; }
            public int CellWidth { get; set; }
            public int CellHeight { get; set; }
            public int LandscapeCellWidth { get; set; }
            public int LandscapeCellHeight { get; set; }
            public int PortraitCellWidth { get; set; }
            public int PortraitCellHeight { get; set; }
            public int Rows { get; set; }
            public int Columns { get; set; }
            public int UnitRows { get; set; }
            public int UnitColumns { get; set; }
        }

        private class CellPhotoCache
        {
            public string Path { get; set; } = string.Empty;
            public PhotoOrientation Orientation { get; set; }
            public RgbColor AverageColor { get; set; }
            public Image<Rgb24> ResizedLandscapeImage { get; set; } = null!;
            public Image<Rgb24> ResizedPortraitImage { get; set; } = null!;
            public CellQuadrantColors LandscapeQuadrants { get; set; }
            public CellQuadrantColors PortraitQuadrants { get; set; }
            public int UseCount { get; set; }
        }
    }

    /// <summary>
    /// Simple cross-platform RGB color struct (no System.Drawing dependency).
    /// </summary>
    public readonly record struct RgbColor(byte R, byte G, byte B);
}
