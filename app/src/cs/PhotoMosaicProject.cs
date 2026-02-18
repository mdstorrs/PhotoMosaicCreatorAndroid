namespace PhotoMosaicCreator.Core.Models
{
    public enum PhotoOrientation
    {
        Landscape,
        Portrait,
        Square
    }

    public class CellPhoto
    {
        public string Path { get; set; } = string.Empty;
        public PhotoOrientation Orientation { get; set; } = PhotoOrientation.Landscape;
    }

    public class PrintSizeConfig
    {
        public string Name { get; set; } = string.Empty;
        public double Width { get; set; }
        public double Height { get; set; }
        public bool IsCustom { get; set; } = false;

        public override string ToString() => Name;
    }

    public class ResolutionConfig
    {
        public int PPI { get; set; }

        public override string ToString() => $"{PPI} PPI";
    }

    public class CellSizeConfig
    {
        public string Name { get; set; } = string.Empty;
        public double SizeMm { get; set; }
        public bool IsCustom { get; set; } = false;

        public override string ToString() => Name;
    }

    public class CellPhotoPatternConfig
    {
        public string Name { get; set; } = string.Empty;

        public override string ToString() => Name;
    }

    public class MaxPhotoUseConfig
    {
        public string Name { get; set; } = string.Empty;
        public int? MaxUses { get; set; }
        public bool IsCustom { get; set; } = false;

        public override string ToString() => Name;
    }

    public class DuplicateSpacingConfig
    {
        public string Name { get; set; } = string.Empty;
        public int MinSpacing { get; set; }

        public override string ToString() => Name;
    }

    public class ColorChangeConfig
    {
        public string Name { get; set; } = string.Empty;
        public int PercentageChange { get; set; }
        public bool IsCustom { get; set; } = false;

        public override string ToString() => Name;
    }

    public enum PrimaryImageSizingMode
    {
        CropToPrintSize,
        KeepAspectRatio
    }

    public enum CellShape
    {
        Square,
        Rectangle4x3,
        Rectangle3x2
    }

    public enum CellImageFitMode
    {
        CropToFit,
        StretchToFit
    }

    public class PhotoMosaicProject
    {
        public string? PrimaryImagePath { get; set; }
        public List<CellPhoto> CellPhotos { get; set; } = [];
        public PrintSizeConfig? SelectedPrintSize { get; set; }
        public double CustomWidth { get; set; } = 8;
        public double CustomHeight { get; set; } = 12;
        public PrimaryImageSizingMode PrimaryImageSizingMode { get; set; } = PrimaryImageSizingMode.CropToPrintSize;
        public ResolutionConfig? SelectedResolution { get; set; }
        public CellSizeConfig? SelectedCellSize { get; set; }
        public double CustomCellSize { get; set; } = 15;
        public CellShape CellShape { get; set; } = CellShape.Square;
        public CellImageFitMode CellImageFitMode { get; set; } = CellImageFitMode.CropToFit;
        public CellPhotoPatternConfig? SelectedCellPhotoPattern { get; set; }
        public MaxPhotoUseConfig? SelectedMaxPhotoUse { get; set; }
        public int CustomMaxPhotoUse { get; set; } = 5;
        public DuplicateSpacingConfig? SelectedDuplicateSpacing { get; set; }
        public ColorChangeConfig? SelectedColorChange { get; set; }
        public int CustomColorChange { get; set; } = 10;
        public int RandomCellCandidates { get; set; } = 5;
        public bool MirrorCellPhotos { get; set; } = true;
        public bool UseAllImages { get; set; } = false;
        public bool CreateReport { get; set; } = true;

        // Window state properties (used by desktop UI, ignored by mobile)
        public double WindowWidth { get; set; } = 1000;
        public double WindowHeight { get; set; } = 750;
        public double WindowLeft { get; set; } = 100;
        public double WindowTop { get; set; } = 100;

        public int TotalCellPhotoCount => CellPhotos.Count;
        public int LandscapePhotoCount => CellPhotos.Count(p => p.Orientation == PhotoOrientation.Landscape);
        public int PortraitPhotoCount => CellPhotos.Count(p => p.Orientation == PhotoOrientation.Portrait);
    }
}
