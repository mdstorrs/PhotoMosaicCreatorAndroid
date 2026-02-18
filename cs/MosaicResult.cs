namespace PhotoMosaicCreator.Core.Models
{
    public class MosaicResult
    {
        public string? TemporaryFilePath { get; set; }
        public string? OverlayImagePath { get; set; }
        public int OverlayOpacityPercent { get; set; }
        public int GridRows { get; set; }
        public int GridColumns { get; set; }
        public int OutputWidth { get; set; }
        public int OutputHeight { get; set; }
        public long GenerationTimeMs { get; set; }
        public string? UsageReportPath { get; set; }
        public int TotalCellPhotos { get; set; }
        public int UsedCellPhotos { get; set; }
        public int UnusedCellPhotos => Math.Max(0, TotalCellPhotos - UsedCellPhotos);
        public string? ErrorMessage { get; set; }
        public bool IsSuccess => string.IsNullOrEmpty(ErrorMessage);
    }
}
