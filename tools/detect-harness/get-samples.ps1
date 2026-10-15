# Downloads the 8 still photos the detector was first tuned on (from andrewdcampbell/OpenCV-Document-Scanner).
$names = "desk.JPG","cell_pic.jpg","chart.JPG","dollar_bill.JPG","math_cheat_sheet.JPG","notepad.JPG","receipt.jpg","tax.jpeg"
New-Item -ItemType Directory -Force samples | Out-Null
foreach ($n in $names) { Invoke-WebRequest "https://raw.githubusercontent.com/andrewdcampbell/OpenCV-Document-Scanner/master/sample_images/$n" -OutFile "samples\$n" }
